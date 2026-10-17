// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.gildasledrogoff.faceblur.FaceDetectionLog
import fr.gildasledrogoff.faceblur.data.*
import fr.gildasledrogoff.faceblur.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class BusyOperation { LOADING, SAVING }
enum class UIError { LOAD, SAVE, RENDER }
sealed interface UIMessage {
    data class Saved(val path: String, val uri: Uri, val metadata: MetadataOutcome) : UIMessage
    data class Error(val kind: UIError, val detail: String?) : UIMessage
}

class LoadedImage(val source: SourceImage, val candidates: List<Face>)
data class OutputSignature(
    val blurred: List<FaceBox>,
    val blur: BlurSettings,
    val drawBoxes: Boolean
)

data class Selection(val blurred: Set<RegionId>, val manual: List<ManualRegion>)
data class FaceBlurUIState(
    val image: LoadedImage? = null,
    val busy: BusyOperation? = null,
    val threshold: Float = DEFAULT_THRESHOLD,
    val blur: BlurSettings = BlurSettings(),
    val drawBoxes: Boolean = false,
    val blurred: Set<RegionId> = emptySet(),
    val manual: List<ManualRegion> = emptyList(),
    val nextManualId: Int = 0,
    val history: UndoHistory<Selection> = UndoHistory(),
    val showOverlay: Boolean = true,
    val message: UIMessage? = null,
    val lastSaved: OutputSignature? = null,
    val lastSavedUri: Uri? = null,
) {
    val regions: List<FaceRegion> by lazy {
        image?.let { buildRegions(it.candidates, threshold, manual, blurred) }.orEmpty()
    }
    val detectedCount: Int get() = regions.count { !it.isManual }
    val manualCount: Int get() = manual.size
    val blurredCount: Int get() = regions.count { it.blurred }
    val canSave: Boolean get() = image != null && busy == null
    val signature: OutputSignature by lazy {
        OutputSignature(regions.filter { it.blurred }.map { it.box }, blur, drawBoxes)
    }
    val hasUnsavedChanges: Boolean
        get() = image != null && signature != lastSaved &&
                (lastSaved != null || signature.blurred.isNotEmpty() || manual.isNotEmpty())
    val shareableUri: Uri? get() = lastSavedUri?.takeIf { image != null && signature == lastSaved }
    val selection: Selection get() = Selection(blurred, manual)
    val canUndo: Boolean get() = busy == null && history.canUndo
    val canRedo: Boolean get() = busy == null && history.canRedo
    val canReset: Boolean get() = image != null && busy == null
    val isPristine: Boolean
        get() = !history.canUndo && !history.canRedo && blurred.isEmpty() && manual.isEmpty() &&
                threshold == DEFAULT_THRESHOLD && showOverlay

    fun withSelection(selection: Selection): FaceBlurUIState =
        copy(blurred = selection.blurred, manual = selection.manual)

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val MIN_THRESHOLD = 0.2f
        const val MAX_THRESHOLD = 0.95f
    }
}

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
    private val settingsRepository = SettingsRepository.get(app)
    private val settings = settingsRepository.settings
    private val _state = MutableStateFlow(
        FaceBlurUIState(blur = settings.value.blur, drawBoxes = settings.value.drawBoxes)
    )
    val state: StateFlow<FaceBlurUIState> = _state.asStateFlow()

    init {
        settings.map { it.drawBoxes }.distinctUntilChanged()
            .onEach { value -> _state.update { it.copy(drawBoxes = value) } }
            .launchIn(viewModelScope)
        settings.map { it.blur }.distinctUntilChanged()
            .onEach { value -> _state.update { it.copy(blur = value) } }
            .launchIn(viewModelScope)
    }

    private val _isRendering = MutableStateFlow(false)
    val isRendering: StateFlow<Boolean> = _isRendering.asStateFlow()
    val preview: StateFlow<Preview?> = _state
        .map { current ->
            current.image?.let {
                RenderKey(
                    it.source.bitmap,
                    current.regions.filter(FaceRegion::blurred),
                    current.blur,
                    current.drawBoxes
                )
            }
        }
        .distinctUntilChanged()
        .mapLatest { key -> key?.let { renderPreview(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun renderPreview(key: RenderKey): Preview? {
        _isRendering.value = true
        return try {
            val bitmap = withContext(Dispatchers.Default) {
                FaceDetectionLog.timed(
                    TAG,
                    "Rendering preview (${key.targets.size} zones, ${key.blur.type})"
                ) {
                    FaceRenderer.render(key.source, key.targets, key.blur, key.drawBoxes)
                }
            }
            Preview(key.source, bitmap)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FaceDetectionLog.e(TAG, t) { "Preview rendering failed" }
            _state.update { it.copy(message = UIMessage.Error(UIError.RENDER, t.message)) }
            null
        } finally {
            _isRendering.value = false
        }
    }

    fun open(uri: Uri) = load("Loading $uri", { repository.load(uri) }) {
        it.copy(lastSaved = null, lastSavedUri = null)
    }

    val hasDemoImage: Boolean get() = repository.hasDemoImage
    fun openDemoImage() = load("Loading demo photo", repository::loadDemoImage) {
        it.copy(lastSaved = null, lastSavedUri = null)
    }

    fun reset() {
        val source = _state.value.image?.source ?: return
        FaceDetectionLog.d(TAG) { "Reset" }
        load("Resetting ${source.name}", { source }) {
            it.copy(
                threshold = FaceBlurUIState.DEFAULT_THRESHOLD,
                showOverlay = true,
            )
        }
    }

    private fun load(
        description: String,
        readSource: () -> SourceImage,
        finish: (FaceBlurUIState) -> FaceBlurUIState,
    ) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = BusyOperation.LOADING) }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) {
                    val source = readSource()
                    val candidates = FaceDetectionLog.timed(
                        TAG,
                        "Detecting faces",
                        { "${it.size} candidates" }) {
                        detector.detectCandidates(source.bitmap)
                    }
                    LoadedImage(source, candidates)
                }
                _state.update {
                    finish(
                        it.copy(
                            image = loaded,
                            busy = null,
                            blurred = emptySet(),
                            manual = emptyList(),
                            nextManualId = 0,
                            history = UndoHistory(),
                        )
                    )
                }
                FaceDetectionLog.i(TAG) { "Loaded ${loaded.source.name}: ${_state.value.detectedCount} faces at threshold ${_state.value.threshold}" }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                FaceDetectionLog.e(TAG, t) { "$description failed" }
                _state.update {
                    it.copy(
                        busy = null,
                        message = UIMessage.Error(UIError.LOAD, t.message)
                    )
                }
            }
        }
    }

    fun setThreshold(value: Float) = _state.update { it.copy(threshold = value) }
    fun setBlurType(value: BlurType) = settingsRepository.setBlurType(value)
    fun setStrength(value: Int) = settingsRepository.setBlurStrength(value)
    fun setTilePercent(value: Int) = settingsRepository.setTilePercent(value)

    fun setShowOverlay(value: Boolean) = _state.update { it.copy(showOverlay = value) }
    fun onImageTap(x: Float, y: Float, tolerance: Float) = edit { current ->
        val hit = hitTest(current.regions, x, y, tolerance)
        if (hit == null) {
            val bitmap = current.image?.source?.bitmap ?: return@edit current
            val detected = current.regions.filterNot { it.isManual }.map { it.box }
            return@edit current.withManualRegion(
                tapBox(
                    x,
                    y,
                    detected,
                    bitmap.width,
                    bitmap.height
                )
            )
        }
        val id = hit.id
        when {
            !hit.blurred -> {
                FaceDetectionLog.d(TAG) { "Blur $id" }
                current.copy(blurred = current.blurred + id)
            }

            id is RegionId.Manual -> current.withoutManualRegion(id)
            else -> {
                FaceDetectionLog.d(TAG) { "Exclude $id" }
                current.copy(blurred = current.blurred - id)
            }
        }
    }

    fun setRegionBlurred(id: RegionId, isBlurred: Boolean) = edit { current ->
        FaceDetectionLog.d(TAG) { "${if (isBlurred) "Blur" else "Exclude"} $id from the list" }
        current.copy(blurred = if (isBlurred) current.blurred + id else current.blurred - id)
    }

    fun removeManualRegion(id: RegionId.Manual) = edit { it.withoutManualRegion(id) }
    private fun FaceBlurUIState.withoutManualRegion(id: RegionId.Manual): FaceBlurUIState {
        FaceDetectionLog.d(TAG) { "Delete manual zone ${id.id}" }
        return copy(manual = manual.filterNot { it.id == id.id }, blurred = blurred - id)
    }

    fun addManualRegion(box: FaceBox) = edit { it.withManualRegion(box) }
    private fun FaceBlurUIState.withManualRegion(box: FaceBox): FaceBlurUIState {
        val bitmap = image?.source?.bitmap ?: return this
        val clamped = box.clampedTo(bitmap.width, bitmap.height)
        if (clamped.width < MIN_MANUAL_SIZE || clamped.height < MIN_MANUAL_SIZE) return this
        FaceDetectionLog.d(TAG) { "Manual zone $nextManualId: $clamped" }
        return copy(
            manual = manual + ManualRegion(nextManualId, clamped),
            blurred = blurred + RegionId.Manual(nextManualId),
            nextManualId = nextManualId + 1
        )
    }

    fun blurAll() =
        edit { current -> current.copy(blurred = current.blurred + current.regions.map { it.id }) }
    fun blurNone() = edit { it.copy(blurred = emptySet()) }

    private fun edit(transform: (FaceBlurUIState) -> FaceBlurUIState) = _state.update { current ->
        val next = transform(current)
        if (next.selection == current.selection) next
        else next.copy(history = current.history.record(current.selection))
    }

    fun undo() = _state.update { current ->
        if (!current.canUndo) return@update current
        val (previous, history) = current.history.undo(current.selection) ?: return@update current
        FaceDetectionLog.d(TAG) { "Undo" }
        current.withSelection(previous).copy(history = history)
    }

    fun redo() = _state.update { current ->
        if (!current.canRedo) return@update current
        val (next, history) = current.history.redo(current.selection) ?: return@update current
        FaceDetectionLog.d(TAG) { "Redo" }
        current.withSelection(next).copy(history = history)
    }

    fun save() {
        val snapshot = _state.value
        val image = snapshot.image ?: return
        if (!snapshot.canSave) return
        _state.update { it.copy(busy = BusyOperation.SAVING) }
        viewModelScope.launch {
            try {
                val suffix =
                    listOfNotNull("blur", "bbox".takeIf { snapshot.drawBoxes }).joinToString("_")
                val result = withContext(Dispatchers.Default) {
                    val rendered = FaceRenderer.render(
                        image.source.bitmap,
                        snapshot.regions.filter { it.blurred },
                        snapshot.blur,
                        snapshot.drawBoxes,
                    )
                    withContext(Dispatchers.IO) {
                        val export = settings.value.export
                        FaceDetectionLog.timed(TAG, "Saving $export") {
                            repository.save(rendered, image.source, suffix, export)
                        }
                    }
                }
                _state.update {
                    it.copy(
                        busy = null,
                        message = UIMessage.Saved(result.path, result.uri, result.metadata),
                        lastSaved = snapshot.signature,
                        lastSavedUri = result.uri,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                FaceDetectionLog.e(TAG, t) { "Saving failed" }
                _state.update {
                    it.copy(
                        busy = null,
                        message = UIMessage.Error(UIError.SAVE, t.message)
                    )
                }
            }
        }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    private companion object {
        const val TAG = "ViewModel"
        const val MIN_MANUAL_SIZE = 4f
    }
}
