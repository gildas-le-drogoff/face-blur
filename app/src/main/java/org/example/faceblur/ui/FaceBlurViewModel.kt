package org.example.faceblur.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.faceblur.FaceDetectionLog
import org.example.faceblur.data.FaceRenderer
import org.example.faceblur.data.ImageRepository
import org.example.faceblur.data.MetadataOutcome
import org.example.faceblur.data.ScrfdDetector
import org.example.faceblur.data.SourceImage
import org.example.faceblur.domain.BlurSettings
import org.example.faceblur.domain.BlurShape
import org.example.faceblur.domain.BlurType
import org.example.faceblur.domain.ExportFormat
import org.example.faceblur.domain.ExportOptions
import org.example.faceblur.domain.Face
import org.example.faceblur.domain.FaceBox
import org.example.faceblur.domain.FaceRegion
import org.example.faceblur.domain.ManualRegion
import org.example.faceblur.domain.RegionId
import org.example.faceblur.domain.buildRegions
import org.example.faceblur.domain.hitTest
import org.example.faceblur.domain.toggled

enum class EditMode { SELECT, DRAW }

enum class Busy { LOADING, SAVING }

enum class UiError { LOAD, SAVE, RENDER }

sealed interface UiMessage {
    data class Saved(val path: String, val metadata: MetadataOutcome) : UiMessage
    data class Error(val kind: UiError, val detail: String?) : UiMessage
}

class LoadedImage(val source: SourceImage, val candidates: List<Face>)

data class FaceBlurUiState(
    val image: LoadedImage? = null,
    val busy: Busy? = null,
    val threshold: Float = DEFAULT_THRESHOLD,
    val blur: BlurSettings = BlurSettings(),
    val drawBoxes: Boolean = false,
    val excluded: Set<RegionId> = emptySet(),
    val manual: List<ManualRegion> = emptyList(),
    val nextManualId: Int = 0,
    val mode: EditMode = EditMode.SELECT,
    val showOverlay: Boolean = true,
    val export: ExportOptions = ExportOptions(),
    val message: UiMessage? = null,
) {
    /** Calculé une fois par état (NMS comprise). */
    val regions: List<FaceRegion> by lazy {
        image?.let { buildRegions(it.candidates, threshold, manual, excluded) }.orEmpty()
    }
    val detectedCount: Int get() = regions.count { !it.isManual }
    val manualCount: Int get() = manual.size
    val blurredCount: Int get() = regions.count { it.blurred }
    val canSave: Boolean get() = image != null && busy == null && (blur.enabled || drawBoxes)

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val MIN_THRESHOLD = 0.2f
        const val MAX_THRESHOLD = 0.95f
    }
}

/** Aperçu rendu pour l'image [source] : identique au fichier qui sera enregistré. */
class Preview(val source: Bitmap, val bitmap: Bitmap)

private data class RenderKey(
    val source: Bitmap,
    val targets: List<FaceRegion>,
    val blur: BlurSettings,
    val drawBoxes: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FaceBlurViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ImageRepository(app)
    private val detectorLazy = lazy { ScrfdDetector(app) }
    private val detector by detectorLazy

    private val _state = MutableStateFlow(FaceBlurUiState())
    val state: StateFlow<FaceBlurUiState> = _state.asStateFlow()

    private val _rendering = MutableStateFlow(false)
    val rendering: StateFlow<Boolean> = _rendering.asStateFlow()

    /** Recalculé uniquement quand ce qui est flouté change (pas pour le mode, le zoom…). */
    val preview: StateFlow<Preview?> = _state
        .map { s -> s.image?.let { RenderKey(it.source.bitmap, s.regions.filter(FaceRegion::blurred), s.blur, s.drawBoxes) } }
        .distinctUntilChanged()
        .mapLatest { key -> key?.let { renderPreview(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun renderPreview(key: RenderKey): Preview? {
        _rendering.value = true
        return try {
            val bmp = withContext(Dispatchers.Default) {
                FaceDetectionLog.timed(TAG, "Rendering preview (${key.targets.size} zones, ${key.blur.type})") {
                    FaceRenderer.render(key.source, key.targets, key.blur, key.drawBoxes)
                }
            }
            Preview(key.source, bmp)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // OutOfMemoryError compris : on garde l'image d'origine affichée.
            FaceDetectionLog.e(TAG, t) { "Preview rendering failed" }
            _state.update { it.copy(message = UiMessage.Error(UiError.RENDER, t.message)) }
            null
        } finally {
            _rendering.value = false
        }
    }

    fun open(uri: Uri) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = Busy.LOADING) }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) {
                    val source = repository.load(uri)
                    val candidates = FaceDetectionLog.timed(TAG, "Detecting faces", { "${it.size} candidates" }) {
                        detector.detectCandidates(source.bitmap)
                    }
                    LoadedImage(source, candidates)
                }
                _state.update {
                    it.copy(
                        image = loaded,
                        busy = null,
                        excluded = emptySet(),
                        manual = emptyList(),
                        nextManualId = 0,
                        mode = EditMode.SELECT,
                    )
                }
                FaceDetectionLog.i(TAG) { "Loaded ${loaded.source.name}: ${_state.value.detectedCount} faces at default threshold" }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                FaceDetectionLog.e(TAG, t) { "Loading $uri failed" }
                _state.update { it.copy(busy = null, message = UiMessage.Error(UiError.LOAD, t.message)) }
            }
        }
    }

    fun setThreshold(value: Float) = _state.update { it.copy(threshold = value) }

    fun setBlurEnabled(value: Boolean) = updateBlur { it.copy(enabled = value) }
    fun setBlurType(value: BlurType) = updateBlur { it.copy(type = value) }
    fun setStrength(value: Int) = updateBlur { it.copy(strength = value) }
    fun setTilePercent(value: Int) = updateBlur { it.copy(tilePercent = value) }
    fun setShape(value: BlurShape) = updateBlur { it.copy(shape = value) }

    private fun updateBlur(transform: (BlurSettings) -> BlurSettings) =
        _state.update { it.copy(blur = transform(it.blur)) }

    fun setDrawBoxes(value: Boolean) = _state.update { it.copy(drawBoxes = value) }
    fun setMode(value: EditMode) = _state.update { it.copy(mode = value) }
    fun setShowOverlay(value: Boolean) = _state.update { it.copy(showOverlay = value) }
    fun setFormat(value: ExportFormat) = _state.update { it.copy(export = it.export.copy(format = value)) }
    fun setKeepMetadata(value: Boolean) = _state.update { it.copy(export = it.export.copy(keepMetadata = value)) }

    /**
     * Toucher en ([x], [y]) (coordonnées image). Sélection : bascule flouté/exclu.
     * Tracé : supprime la zone manuelle touchée.
     */
    fun onImageTap(x: Float, y: Float, tolerance: Float) = _state.update { s ->
        val hit = hitTest(s.regions, x, y, tolerance) ?: return@update s
        when (s.mode) {
            EditMode.SELECT -> {
                FaceDetectionLog.d(TAG) { "Toggle ${hit.id} (blurred=${hit.blurred})" }
                s.copy(excluded = s.excluded.toggled(hit.id))
            }
            EditMode.DRAW -> {
                val id = hit.id as? RegionId.Manual ?: return@update s
                FaceDetectionLog.d(TAG) { "Delete manual zone ${id.id}" }
                s.copy(manual = s.manual.filterNot { it.id == id.id }, excluded = s.excluded - id)
            }
        }
    }

    /** Ajoute une zone tracée à la main, rognée à l'image ; ignorée si quasi vide. */
    fun addManualRegion(box: FaceBox) = _state.update { s ->
        val bmp = s.image?.source?.bitmap ?: return@update s
        val clamped = box.clampedTo(bmp.width, bmp.height)
        if (clamped.width < MIN_MANUAL_SIZE || clamped.height < MIN_MANUAL_SIZE) return@update s
        FaceDetectionLog.d(TAG) { "Manual zone ${s.nextManualId}: $clamped" }
        s.copy(manual = s.manual + ManualRegion(s.nextManualId, clamped), nextManualId = s.nextManualId + 1)
    }

    fun blurAll() = _state.update { it.copy(excluded = emptySet()) }

    /** Exclut toutes les zones visibles : point de départ pour ne flouter que quelques personnes d'une foule. */
    fun blurNone() = _state.update { s -> s.copy(excluded = s.excluded + s.regions.map { it.id }) }

    fun save() {
        val s = _state.value
        val image = s.image ?: return
        if (!s.canSave) return
        _state.update { it.copy(busy = Busy.SAVING) }
        viewModelScope.launch {
            try {
                val suffix = listOfNotNull("blur".takeIf { s.blur.enabled }, "bbox".takeIf { s.drawBoxes }).joinToString("_")
                val result = withContext(Dispatchers.Default) {
                    val rendered = FaceRenderer.render(
                        image.source.bitmap, s.regions.filter { it.blurred }, s.blur, s.drawBoxes,
                    )
                    withContext(Dispatchers.IO) {
                        FaceDetectionLog.timed(TAG, "Saving ${s.export}") {
                            repository.save(rendered, image.source, suffix, s.export)
                        }
                    }
                }
                _state.update { it.copy(busy = null, message = UiMessage.Saved(result.path, result.metadata)) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                FaceDetectionLog.e(TAG, t) { "Saving failed" }
                _state.update { it.copy(busy = null, message = UiMessage.Error(UiError.SAVE, t.message)) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        if (detectorLazy.isInitialized()) detector.close()
    }

    private companion object {
        const val TAG = "ViewModel"

        /** Côté minimal (px image) d'une zone tracée, pour ignorer un simple effleurement. */
        const val MIN_MANUAL_SIZE = 4f
    }
}
