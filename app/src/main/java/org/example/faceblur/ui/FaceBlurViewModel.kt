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
import org.example.faceblur.data.SourceImage
import org.example.faceblur.data.YuNetDetector
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
import org.example.faceblur.domain.tapBox

enum class Busy { LOADING, SAVING }

enum class UiError { LOAD, SAVE, RENDER }

sealed interface UiMessage {
    data class Saved(val path: String, val metadata: MetadataOutcome) : UiMessage
    data class Error(val kind: UiError, val detail: String?) : UiMessage
}

class LoadedImage(val source: SourceImage, val candidates: List<Face>)

/** Ce qui détermine les pixels exportés : sert à savoir si le dernier enregistrement est à jour. */
data class OutputSignature(val blurred: List<FaceBox>, val blur: BlurSettings, val drawBoxes: Boolean)

data class FaceBlurUiState(
    val image: LoadedImage? = null,
    val busy: Busy? = null,
    val threshold: Float = DEFAULT_THRESHOLD,
    val blur: BlurSettings = BlurSettings(),
    val drawBoxes: Boolean = false,
    val excluded: Set<RegionId> = emptySet(),
    val manual: List<ManualRegion> = emptyList(),
    val nextManualId: Int = 0,
    val showOverlay: Boolean = true,
    val export: ExportOptions = ExportOptions(),
    val message: UiMessage? = null,
    val lastSaved: OutputSignature? = null,
) {
    /** Calculé une fois par état (NMS comprise). */
    val regions: List<FaceRegion> by lazy {
        image?.let { buildRegions(it.candidates, threshold, manual, excluded) }.orEmpty()
    }
    val detectedCount: Int get() = regions.count { !it.isManual }
    val manualCount: Int get() = manual.size
    val blurredCount: Int get() = regions.count { it.blurred }
    val canSave: Boolean get() = image != null && busy == null && (blur.enabled || drawBoxes)
    val signature: OutputSignature by lazy { OutputSignature(regions.filter { it.blurred }.map { it.box }, blur, drawBoxes) }

    /** Image ouverte dont le rendu actuel n'a pas été enregistré (rien de perdu si rien à exporter). */
    val hasUnsavedChanges: Boolean get() = image != null && (blur.enabled || drawBoxes) && signature != lastSaved

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
    private val detector by lazy { YuNetDetector(app) }

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
                        lastSaved = null,
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
    fun setShowOverlay(value: Boolean) = _state.update { it.copy(showOverlay = value) }
    fun setFormat(value: ExportFormat) = _state.update { it.copy(export = it.export.copy(format = value)) }
    fun setKeepMetadata(value: Boolean) = _state.update { it.copy(export = it.export.copy(keepMetadata = value)) }

    /**
     * Toucher en ([x], [y]) (coordonnées image), un seul geste pour tout :
     * hors de toute zone → nouvelle zone floutée ; zone exclue → floutée ;
     * zone floutée → retirée (exclue si détectée, pour pouvoir la reprendre ; supprimée si tracée).
     */
    fun onImageTap(x: Float, y: Float, tolerance: Float) = _state.update { s ->
        val hit = hitTest(s.regions, x, y, tolerance)
        if (hit == null) {
            val bmp = s.image?.source?.bitmap ?: return@update s
            val detected = s.regions.filterNot { it.isManual }.map { it.box }
            return@update s.withManualRegion(tapBox(x, y, detected, bmp.width, bmp.height))
        }
        val id = hit.id
        when {
            !hit.blurred -> {
                FaceDetectionLog.d(TAG) { "Blur $id" }
                s.copy(excluded = s.excluded - id)
            }
            id is RegionId.Manual -> {
                FaceDetectionLog.d(TAG) { "Delete manual zone ${id.id}" }
                s.copy(manual = s.manual.filterNot { it.id == id.id }, excluded = s.excluded - id)
            }
            else -> {
                FaceDetectionLog.d(TAG) { "Exclude $id" }
                s.copy(excluded = s.excluded + id)
            }
        }
    }

    fun addManualRegion(box: FaceBox) = _state.update { it.withManualRegion(box) }

    /** Ajoute une zone manuelle, rognée à l'image ; ignorée si quasi vide. */
    private fun FaceBlurUiState.withManualRegion(box: FaceBox): FaceBlurUiState {
        val bmp = image?.source?.bitmap ?: return this
        val clamped = box.clampedTo(bmp.width, bmp.height)
        if (clamped.width < MIN_MANUAL_SIZE || clamped.height < MIN_MANUAL_SIZE) return this
        FaceDetectionLog.d(TAG) { "Manual zone $nextManualId: $clamped" }
        return copy(manual = manual + ManualRegion(nextManualId, clamped), nextManualId = nextManualId + 1)
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
                // Signature de l'état enregistré, pas de l'état courant : il a pu changer pendant l'écriture.
                _state.update {
                    it.copy(busy = null, message = UiMessage.Saved(result.path, result.metadata), lastSaved = s.signature)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                FaceDetectionLog.e(TAG, t) { "Saving failed" }
                _state.update { it.copy(busy = null, message = UiMessage.Error(UiError.SAVE, t.message)) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private companion object {
        const val TAG = "ViewModel"

        /** Côté minimal (px image) d'une zone tracée, pour ignorer un simple effleurement. */
        const val MIN_MANUAL_SIZE = 4f
    }
}
