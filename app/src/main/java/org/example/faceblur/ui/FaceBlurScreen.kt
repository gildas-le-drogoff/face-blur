package org.example.faceblur.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.faceblur.R
import org.example.faceblur.data.MetadataOutcome
import org.example.faceblur.domain.BlurShape
import org.example.faceblur.domain.BlurType
import org.example.faceblur.domain.ExportFormat
import org.example.faceblur.domain.MAX_BLUR_STRENGTH
import org.example.faceblur.domain.MAX_TILE_PERCENT
import org.example.faceblur.domain.MIN_BLUR_STRENGTH
import org.example.faceblur.domain.MIN_TILE_PERCENT
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceBlurScreen(viewModel: FaceBlurViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val rendering by viewModel.rendering.collectAsStateWithLifecycle()
    val widthClass = currentWidthClass()
    val dimens = dimensFor(widthClass)
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.open(uri)
    }
    val openPicker = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val unknown = resources.getString(R.string.error_unknown)
        val text = when (message) {
            is UiMessage.Saved -> resources.getString(
                when (message.metadata) {
                    MetadataOutcome.STRIPPED -> R.string.msg_saved
                    MetadataOutcome.KEPT -> R.string.msg_saved_metadata_kept
                    MetadataOutcome.NONE_AVAILABLE -> R.string.msg_saved_no_metadata
                    MetadataOutcome.FAILED -> R.string.msg_saved_metadata_failed
                },
                message.path,
            )
            is UiMessage.Error -> resources.getString(
                when (message.kind) {
                    UiError.LOAD -> R.string.error_load
                    UiError.SAVE -> R.string.error_save
                    UiError.RENDER -> R.string.error_render
                },
                message.detail ?: unknown,
            )
        }
        viewModel.messageShown()
        snackbar.showSnackbar(text)
    }

    CompositionLocalProvider(LocalDimens provides dimens) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = openPicker, enabled = state.busy == null) {
                            Icon(painterResource(R.drawable.ic_add_photo_alternate), stringResource(R.string.action_open))
                        }
                        IconButton(onClick = viewModel::save, enabled = state.canSave) {
                            Icon(painterResource(R.drawable.ic_save), stringResource(R.string.action_save))
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val editor = @Composable { modifier: Modifier ->
                EditorPane(state, preview, rendering, viewModel, openPicker, modifier)
            }
            val controls = @Composable { modifier: Modifier ->
                ControlsPanel(state, viewModel, modifier)
            }
            if (widthClass == WidthClass.COMPACT) {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(dimens.screenPadding),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacing),
                ) {
                    editor(Modifier.fillMaxWidth().weight(dimens.previewWeight))
                    controls(Modifier.fillMaxWidth().weight(dimens.controlsWeight))
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(padding).padding(dimens.screenPadding),
                    horizontalArrangement = Arrangement.spacedBy(dimens.sectionSpacing),
                ) {
                    editor(Modifier.fillMaxSize().weight(dimens.previewWeight))
                    controls(Modifier.fillMaxSize().weight(dimens.controlsWeight))
                }
            }
        }
    }
}

@Composable
private fun EditorPane(
    state: FaceBlurUiState,
    preview: Preview?,
    rendering: Boolean,
    viewModel: FaceBlurViewModel,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    val dimens = LocalDimens.current
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = state.image
        if (image == null) {
            if (state.busy == null) EmptyState(onOpen)
        } else {
            // Tant que l'aperçu correspond à une autre image, on montre l'original.
            val shown = preview?.takeIf { it.source === image.source.bitmap }?.bitmap ?: image.source.bitmap
            val imageBitmap = remember(shown) { shown.asImageBitmap() }
            val zoom = remember(image) { ZoomState() }
            // Barre d'outils et aide hors de l'image : rien ne masque ni n'intercepte les visages.
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                EditorToolbar(state, zoom, viewModel)
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
                        mode = state.mode,
                        showOverlay = state.showOverlay,
                        zoom = zoom,
                        description = stringResource(R.string.cd_preview, state.blurredCount, state.regions.size),
                        onTap = viewModel::onImageTap,
                        onBoxDrawn = viewModel::addManualRegion,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Nom qualifié : sinon la variante ColumnScope du parent serait choisie.
                    androidx.compose.animation.AnimatedVisibility(
                        rendering,
                        Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                Text(
                    stringResource(if (state.mode == EditMode.SELECT) R.string.hint_select else R.string.hint_draw),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        AnimatedVisibility(state.busy == Busy.LOADING) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    val dimens = LocalDimens.current
    Column(
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
    }
}

@Composable
private fun EditorToolbar(state: FaceBlurUiState, zoom: ZoomState, viewModel: FaceBlurViewModel) {
    val dimens = LocalDimens.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            val modes = listOf(
                Triple(EditMode.SELECT, R.drawable.ic_touch_app, R.string.mode_select),
                Triple(EditMode.DRAW, R.drawable.ic_highlight_alt, R.string.mode_draw),
            )
            modes.forEachIndexed { index, (mode, icon, label) ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { viewModel.setMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    icon = { Icon(painterResource(icon), contentDescription = null) },
                ) { Text(stringResource(label)) }
            }
        }
        IconButton(onClick = { viewModel.setShowOverlay(!state.showOverlay) }) {
            Icon(
                painterResource(if (state.showOverlay) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                stringResource(if (state.showOverlay) R.string.cd_hide_overlay else R.string.cd_show_overlay),
            )
        }
        IconButton(onClick = zoom::reset, enabled = zoom.isZoomed) {
            Icon(painterResource(R.drawable.ic_fit_screen), stringResource(R.string.cd_fit_screen))
        }
    }
}

@Composable
private fun ControlsPanel(state: FaceBlurUiState, viewModel: FaceBlurViewModel, modifier: Modifier) {
    val dimens = LocalDimens.current
    val enabled = state.busy == null
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        if (state.image != null) {
            SectionTitle(R.string.section_detection)
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.detected_faces, state.detectedCount, state.detectedCount),
                    state.manualCount.takeIf { it > 0 }?.let {
                        pluralStringResource(R.plurals.manual_zones, it, it)
                    },
                    stringResource(R.string.blurred_summary, state.blurredCount, state.regions.size),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            LabeledSlider(
                label = stringResource(R.string.threshold_label, state.threshold),
                value = state.threshold,
                onValueChange = viewModel::setThreshold,
                range = FaceBlurUiState.MIN_THRESHOLD..FaceBlurUiState.MAX_THRESHOLD,
                enabled = enabled,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(dimens.spacing)) {
                OutlinedButton(onClick = viewModel::blurAll, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_blur_all))
                }
                OutlinedButton(onClick = viewModel::blurNone, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_blur_none))
                }
            }
        }

        SectionTitle(R.string.section_blur)
        SwitchRow(stringResource(R.string.blur_enabled), state.blur.enabled, viewModel::setBlurEnabled, enabled)
        AnimatedVisibility(state.blur.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.spacing)) {
                Text(stringResource(R.string.blur_type), style = MaterialTheme.typography.labelLarge)
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
                )
                when (state.blur.type) {
                    BlurType.GAUSSIAN -> IntSlider(
                        label = stringResource(R.string.blur_strength, state.blur.strength),
                        value = state.blur.strength,
                        range = MIN_BLUR_STRENGTH..MAX_BLUR_STRENGTH,
                        onValueChange = viewModel::setStrength,
                        enabled = enabled,
                    )
                    BlurType.MOSAIC -> IntSlider(
                        label = stringResource(R.string.tile_size, state.blur.tilePercent),
                        value = state.blur.tilePercent,
                        range = MIN_TILE_PERCENT..MAX_TILE_PERCENT,
                        onValueChange = viewModel::setTilePercent,
                        enabled = enabled,
                    )
                }
                Text(stringResource(R.string.blur_shape), style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = BlurShape.entries,
                    selected = state.blur.shape,
                    label = {
                        when (it) {
                            BlurShape.RECT -> stringResource(R.string.shape_rect)
                            BlurShape.OVAL -> stringResource(R.string.shape_oval)
                        }
                    },
                    onSelect = viewModel::setShape,
                    enabled = enabled,
                )
            }
        }
        SwitchRow(stringResource(R.string.draw_boxes), state.drawBoxes, viewModel::setDrawBoxes, enabled)

        SectionTitle(R.string.section_export)
        Text(stringResource(R.string.export_format), style = MaterialTheme.typography.labelLarge)
        ChoiceRow(
            options = ExportFormat.entries,
            selected = state.export.format,
            label = {
                when (it) {
                    ExportFormat.JPEG -> stringResource(R.string.format_jpeg)
                    ExportFormat.PNG -> stringResource(R.string.format_png)
                    ExportFormat.WEBP -> stringResource(R.string.format_webp)
                }
            },
            onSelect = viewModel::setFormat,
            enabled = enabled,
        )
        SwitchRow(
            text = stringResource(R.string.keep_metadata),
            checked = state.export.keepMetadata,
            onCheckedChange = viewModel::setKeepMetadata,
            enabled = enabled,
            supporting = stringResource(
                if (state.export.keepMetadata) R.string.metadata_kept_hint else R.string.metadata_stripped_hint,
            ),
        )
        Button(onClick = viewModel::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = LocalDimens.current.spacing),
    )
}

@Composable
private fun SwitchRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    supporting: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
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
        // Clic géré par la ligne entière (cible tactile plus grande, un seul nœud d'accessibilité).
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                enabled = enabled,
            ) { Text(label(option)) }
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
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps, enabled = enabled)
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, onValueChange: (Int) -> Unit, enabled: Boolean) {
    LabeledSlider(
        label = label,
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        range = range.first.toFloat()..range.last.toFloat(),
        enabled = enabled,
        steps = range.last - range.first - 1,
    )
}

