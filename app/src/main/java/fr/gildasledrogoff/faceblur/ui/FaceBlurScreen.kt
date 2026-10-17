// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.faceblur.R
import fr.gildasledrogoff.faceblur.data.MetadataOutcome
import fr.gildasledrogoff.faceblur.domain.*
import fr.gildasledrogoff.faceblur.shareImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun FaceBlurScreen(
    viewModel: FaceBlurViewModel,
    settingsViewModel: SettingsViewModel,
    openPicker: () -> Unit,
    onSaved: ((Uri) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val widthClass = currentWidthClass()
    val dimens = dimensFor(widthClass)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    var backStack by rememberSaveable(stateSaver = BackStackSaver) {
        mutableStateOf(listOf<Destination>(Destination.Editor))
    }
    val destination = backStack.last()
    val navigate = { to: Destination -> if (destination != to) backStack = backStack + to }
    val navigateBack = { backStack = backStack.dropLast(1) }
    BackHandler(enabled = backStack.size > 1, onBack = navigateBack)
    LaunchedEffect(state.message, destination) {
        val message = state.message ?: return@LaunchedEffect
        if (destination != Destination.Editor) return@LaunchedEffect
        if (message is UIMessage.Saved && onSaved != null) {
            viewModel.onMessageShown()
            onSaved(message.uri)
            return@LaunchedEffect
        }
        val unknownError = resources.getString(R.string.error_unknown)
        val messageText = when (message) {
            is UIMessage.Saved -> resources.getString(
                when (message.metadata) {
                    MetadataOutcome.STRIPPED -> R.string.msg_saved
                    MetadataOutcome.KEPT -> R.string.msg_saved_metadata_kept
                    MetadataOutcome.NONE_AVAILABLE -> R.string.msg_saved_no_metadata
                    MetadataOutcome.FAILED -> R.string.msg_saved_metadata_failed
                },
                message.path,
            )

            is UIMessage.Error -> resources.getString(
                when (message.kind) {
                    UIError.LOAD -> R.string.error_load
                    UIError.SAVE -> R.string.error_save
                    UIError.RENDER -> R.string.error_render
                },
                message.detail ?: unknownError,
            )
        }
        val shareUri = (message as? UIMessage.Saved)?.uri
        scope.launch {
            if (shareUri == null) {
                snackbar.showSnackbar(messageText)
            } else {
                val result = snackbar.showSnackbar(
                    messageText,
                    actionLabel = resources.getString(R.string.action_share),
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) context.shareImage(shareUri)
            }
        }
        viewModel.onMessageShown()
    }
    CompositionLocalProvider(LocalDimens provides dimens) {
        AnimatedContent(
            backStack,
            transitionSpec = {
                if (targetState.size > initialState.size) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            contentKey = { it.last() },
            label = "destination",
        ) { stack ->
            when (stack.last()) {
                Destination.Editor -> EditorScreen(
                    state = state,
                    viewModel = viewModel,
                    snackbar = snackbar,
                    onOpen = openPicker,
                    onSettings = { navigate(Destination.Settings) },
                    onCredits = { navigate(Destination.Credits) },
                )

                Destination.Settings -> SettingsScreen(
                    viewModel = settingsViewModel,
                    onCredits = { navigate(Destination.Credits) },
                    onBack = navigateBack,
                )

                Destination.Credits -> CreditsLicensesScreen(onBack = navigateBack)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    state: FaceBlurUIState,
    viewModel: FaceBlurViewModel,
    snackbar: SnackbarHostState,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
    onCredits: () -> Unit,
) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val isRendering by viewModel.isRendering.collectAsStateWithLifecycle()
    val widthClass = currentWidthClass()
    val dimens = LocalDimens.current
    val activity = LocalActivity.current
    var isExitDialogShown by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.hasUnsavedChanges) { isExitDialogShown = true }
    if (isExitDialogShown) {
        AlertDialog(
            onDismissRequest = { isExitDialogShown = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = {
                TextButton(onClick = { isExitDialogShown = false; activity?.finish() }) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    isExitDialogShown = false
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    val dropTarget = remember(activity, viewModel) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val dragEvent = event.toAndroidDragEvent()
                val uri = dragEvent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                    ?: return false
                activity?.requestDragAndDropPermissions(dragEvent)
                viewModel.open(uri)
                return true
            }
        }
    }
    Scaffold(
        modifier = Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                state.busy == null && event.mimeTypes().any { it.startsWith("image/") }
            },
            target = dropTarget,
        ),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                onClickLabel = stringResource(R.string.credits_title),
                                role = Role.Button,
                                onClick = onCredits,
                            )
                            .minimumInteractiveComponentSize()
                            .padding(horizontal = dimens.spacing / 2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onOpen, enabled = state.busy == null) {
                        Icon(
                            painterResource(R.drawable.ic_add_photo_alternate),
                            stringResource(R.string.action_open)
                        )
                    }
                    IconButton(onClick = viewModel::save, enabled = state.canSave) {
                        Icon(
                            painterResource(R.drawable.ic_save),
                            stringResource(R.string.action_save)
                        )
                    }
                    val shareUri = state.shareableUri
                    if (shareUri != null) {
                        val context = LocalContext.current
                        IconButton(onClick = { context.shareImage(shareUri) }) {
                            Icon(
                                painterResource(R.drawable.ic_share),
                                stringResource(R.string.action_share)
                            )
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            painterResource(R.drawable.ic_settings),
                            stringResource(R.string.settings_title)
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val editor = @Composable { modifier: Modifier ->
            EditorPane(state, preview, isRendering, viewModel, onOpen, modifier)
        }
        val controls = @Composable { modifier: Modifier ->
            ControlsPanel(state, viewModel, modifier)
        }
        if (useStackedLayout(widthClass)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(dimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                editor(
                    Modifier
                        .fillMaxWidth()
                        .weight(dimens.previewWeight)
                )
                controls(
                    Modifier
                        .fillMaxWidth()
                        .weight(dimens.controlsWeight)
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(dimens.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(dimens.sectionSpacing),
            ) {
                editor(
                    Modifier
                        .fillMaxSize()
                        .weight(dimens.previewWeight)
                )
                controls(
                    Modifier
                        .fillMaxSize()
                        .weight(dimens.controlsWeight)
                )
            }
        }
    }
}

@Composable
private fun EditorPane(
    state: FaceBlurUIState,
    preview: Preview?,
    isRendering: Boolean,
    viewModel: FaceBlurViewModel,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    val dimens = LocalDimens.current
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = state.image
        if (image == null) {
            if (state.busy == null) {
                EmptyState(onOpen, onOpenDemo = viewModel::openDemoImage.takeIf { viewModel.hasDemoImage })
            }
        } else {
            val displayedBitmap =
                preview?.takeIf { it.source === image.source.bitmap }?.bitmap ?: image.source.bitmap
            val imageBitmap = remember(displayedBitmap) { displayedBitmap.asImageBitmap() }
            val zoom = remember(image) { ZoomState() }
            var isPanModeEnabled by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = zoom.isZoomed) { zoom.reset() }
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                EditorToolbar(
                    state = state,
                    viewModel = viewModel,
                    zoom = zoom,
                    isPanModeEnabled = isPanModeEnabled,
                    onPanModeChange = { isPanModeEnabled = it },
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    ZoomableFaceCanvas(
                        image = imageBitmap,
                        regions = state.regions,
                        showOverlay = state.showOverlay,
                        zoom = zoom,
                        description = stringResource(
                            R.string.cd_preview,
                            state.blurredCount,
                            state.regions.size
                        ),
                        onTap = viewModel::onImageTap,
                        onBoxDrawn = viewModel::addManualRegion,
                        modifier = Modifier.fillMaxSize(),
                        isPanModeEnabled = isPanModeEnabled,
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        isRendering,
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    ) {
                        val label = stringResource(R.string.rendering)
                        LinearProgressIndicator(
                            Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = label })
                    }
                }
                ImageToggles(
                    state = state,
                    viewModel = viewModel,
                    canReset = state.canReset && (!state.isPristine || zoom.isZoomed),
                    onReset = {
                        zoom.reset()
                        isPanModeEnabled = false
                        viewModel.reset()
                    },
                )
            }
        }
        AnimatedVisibility(state.busy == BusyOperation.LOADING) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(
                    stringResource(R.string.loading),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit, onOpenDemo: (() -> Unit)?) {
    val dimens = LocalDimens.current
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
        if (onOpenDemo != null) {
            OutlinedButton(onClick = onOpenDemo) { Text(stringResource(R.string.action_open_demo)) }
        }
    }
}

@Composable
private fun EditorToolbar(
    state: FaceBlurUIState,
    viewModel: FaceBlurViewModel,
    zoom: ZoomState,
    isPanModeEnabled: Boolean,
    onPanModeChange: (Boolean) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.Center,
    ) {
        SelectionToggle(state, viewModel)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { zoom.zoomBy(1 / ZoomState.ZOOM_STEP) }, enabled = zoom.isZoomed) {
            Icon(painterResource(R.drawable.ic_zoom_out), stringResource(R.string.cd_zoom_out))
        }
        IconButton(onClick = { zoom.zoomBy(ZoomState.ZOOM_STEP) }, enabled = zoom.canZoomIn) {
            Icon(painterResource(R.drawable.ic_zoom_in), stringResource(R.string.cd_zoom_in))
        }
        val isPanning = isPanModeEnabled && zoom.isZoomed
        IconToggleButton(
            checked = isPanning,
            onCheckedChange = onPanModeChange,
            enabled = zoom.isZoomed,
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Icon(painterResource(R.drawable.ic_pan), stringResource(R.string.cd_pan_mode))
        }
        IconButton(onClick = zoom::reset, enabled = zoom.isZoomed) {
            Icon(painterResource(R.drawable.ic_fit_screen), stringResource(R.string.cd_fit_screen))
        }
    }
}

@Composable
private fun ImageToggles(
    state: FaceBlurUIState,
    viewModel: FaceBlurViewModel,
    canReset: Boolean,
    onReset: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !state.showOverlay,
                onClick = { viewModel.setShowOverlay(!state.showOverlay) },
                label = {
                    Text(
                        stringResource(R.string.show_result),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        painterResource(if (state.showOverlay) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        IconButton(onClick = onReset, enabled = canReset) {
            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_reset))
        }
        IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
            Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.action_undo))
        }
        IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
            Icon(painterResource(R.drawable.ic_redo), stringResource(R.string.action_redo))
        }
    }
}

@Composable
private fun ControlsPanel(
    state: FaceBlurUIState,
    viewModel: FaceBlurViewModel,
    modifier: Modifier
) {
    val dimens = LocalDimens.current
    val enabled = state.busy == null
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        if (state.image != null) {
            Text(
                stringResource(R.string.hint_edit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state.blur.type) {
            BlurType.GAUSSIAN -> IntSlider(
                label = stringResource(R.string.blur_strength, state.blur.strength),
                value = state.blur.strength,
                range = MIN_BLUR_STRENGTH..MAX_BLUR_STRENGTH,
                onValueChange = viewModel::setStrength,
                enabled = enabled,
            )

            BlurType.MOSAIC -> IntSlider(
                label = stringResource(
                    R.string.blur_strength,
                    state.blur.tilePercent / TILE_PERCENT_STEP,
                ),
                value = state.blur.tilePercent,
                range = MIN_TILE_PERCENT..MAX_TILE_PERCENT step TILE_PERCENT_STEP,
                onValueChange = viewModel::setTilePercent,
                enabled = enabled,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            Text(
                stringResource(R.string.blur_type),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            ChoiceRow(
                options = BlurType.entries,
                selected = state.blur.type,
                label = {
                    when (it) {
                        BlurType.GAUSSIAN -> stringResource(R.string.blur_type_gaussian)
                        BlurType.MOSAIC -> stringResource(R.string.blur_type_mosaic)
                    }
                },
                onSelect = viewModel::setBlurType,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                compact = true,
            )
        }
        if (state.image != null) {
            // SectionTitle(R.string.section_detection)
            Text(
                listOfNotNull(
                    pluralStringResource(
                        R.plurals.detected_faces,
                        state.detectedCount,
                        state.detectedCount
                    ),
                    state.manualCount.takeIf { it > 0 }?.let {
                        pluralStringResource(R.plurals.manual_zones, it, it)
                    },
                    stringResource(
                        R.string.blurred_summary,
                        state.blurredCount,
                        state.regions.size
                    ),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val thresholdMirror = FaceBlurUIState.MIN_THRESHOLD + FaceBlurUIState.MAX_THRESHOLD
            LabeledSlider(
                label = stringResource(R.string.threshold_label, state.threshold),
                value = thresholdMirror - state.threshold,
                onValueChange = { viewModel.setThreshold(thresholdMirror - it) },
                range = FaceBlurUIState.MIN_THRESHOLD..FaceBlurUIState.MAX_THRESHOLD,
                enabled = enabled,
            )
        }
        Button(
            onClick = viewModel::save,
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun SelectionToggle(
    state: FaceBlurUIState,
    viewModel: FaceBlurViewModel,
    modifier: Modifier = Modifier,
) {
    val blurredCount = state.blurredCount
    val toggleState = when {
        state.regions.isNotEmpty() && blurredCount == state.regions.size -> ToggleableState.On
        blurredCount > 0 -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }
    val label = stringResource(
        if (toggleState == ToggleableState.On) R.string.action_blur_none else R.string.action_blur_all
    )
    TriStateCheckbox(
        state = toggleState,
        onClick = { if (toggleState == ToggleableState.On) viewModel.blurNone() else viewModel.blurAll() },
        enabled = state.busy == null && state.regions.isNotEmpty(),
        modifier = modifier.semantics { contentDescription = label },
    )
}

@Composable
internal fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = LocalDimens.current.spacing)
            .semantics { heading() },
    )
}

@Composable
internal fun SwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    supporting: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = LocalDimens.current.minTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    compact: Boolean = false,
) {
    val dimens = LocalDimens.current
    SingleChoiceSegmentedButtonRow(
        if (compact) modifier.height(dimens.compactButtonHeight) else modifier
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                enabled = enabled,
                contentPadding = if (compact) {
                    PaddingValues(horizontal = dimens.spacing)
                } else {
                    SegmentedButtonDefaults.ContentPadding
                },
            ) {
                Text(
                    label(option),
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        LocalTextStyle.current
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    steps: Int = 0,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.semantics { stateDescription = label },
        )
    }
}

@Composable
private fun IntSlider(
    label: String,
    value: Int,
    range: IntProgression,
    onValueChange: (Int) -> Unit,
    enabled: Boolean
) {
    LabeledSlider(
        label = label,
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        range = range.first.toFloat()..range.last.toFloat(),
        enabled = enabled,
        steps = (range.last - range.first) / range.step - 1,
    )
}
