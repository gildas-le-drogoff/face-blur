package org.example.faceblur.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.example.faceblur.R
import org.example.faceblur.data.MetadataOutcome
import org.example.faceblur.domain.BlurShape
import org.example.faceblur.domain.BlurType
import org.example.faceblur.domain.ExportFormat
import org.example.faceblur.domain.MAX_BLUR_STRENGTH
import org.example.faceblur.domain.MAX_TILE_PERCENT
import org.example.faceblur.domain.MIN_BLUR_STRENGTH
import org.example.faceblur.domain.MIN_TILE_PERCENT
import org.example.faceblur.domain.TILE_PERCENT_STEP
import kotlin.math.roundToInt

@Composable
fun FaceBlurScreen(viewModel: FaceBlurViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val widthClass = currentWidthClass()
    val dimens = dimensFor(widthClass)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var destination by rememberSaveable(stateSaver = DestinationSaver) { mutableStateOf<Destination>(Destination.Editor) }

    // Retour système : referme les crédits avant de quitter l'application.
    BackHandler(enabled = destination == Destination.Credits) { destination = Destination.Editor }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.open(uri)
    }
    val openPicker = { picker.launch(arrayOf("image/*")) }

    LaunchedEffect(state.message, destination) {
        val message = state.message ?: return@LaunchedEffect
        // Le Snackbar est hébergé par l'éditeur : différer le message tant que les crédits sont affichés.
        if (destination != Destination.Editor) return@LaunchedEffect
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
        // Scope indépendant : la remise à null du message relance cet effet sans fermer le snackbar.
        scope.launch { snackbar.showSnackbar(text) }
        viewModel.messageShown()
    }

    CompositionLocalProvider(LocalDimens provides dimens) {
        AnimatedContent(
            destination,
            transitionSpec = {
                if (targetState == Destination.Credits) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "destination",
        ) { dest ->
            when (dest) {
                Destination.Editor -> EditorScreen(
                    state = state,
                    viewModel = viewModel,
                    snackbar = snackbar,
                    onOpen = openPicker,
                    onCredits = { destination = Destination.Credits },
                )
                Destination.Credits -> CreditsLicensesScreen(onBack = { destination = Destination.Editor })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    state: FaceBlurUiState,
    viewModel: FaceBlurViewModel,
    snackbar: SnackbarHostState,
    onOpen: () -> Unit,
    onCredits: () -> Unit,
) {
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val rendering by viewModel.rendering.collectAsStateWithLifecycle()
    val widthClass = currentWidthClass()
    val dimens = LocalDimens.current

    // Sans ce garde-fou, le retour met l'app en arrière-plan et un rendu non enregistré
    // est perdu dès que le système tue le processus.
    val activity = LocalActivity.current
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.hasUnsavedChanges) { confirmExit = true }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; activity?.finish() }) {
                    Text(stringResource(R.string.action_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpen, enabled = state.busy == null) {
                        Icon(painterResource(R.drawable.ic_add_photo_alternate), stringResource(R.string.action_open))
                    }
                    IconButton(onClick = viewModel::save, enabled = state.canSave) {
                        Icon(painterResource(R.drawable.ic_save), stringResource(R.string.action_save))
                    }
                    IconButton(onClick = onCredits) {
                        Icon(painterResource(R.drawable.ic_info), stringResource(R.string.cd_credits))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val editor = @Composable { modifier: Modifier ->
            EditorPane(state, preview, rendering, viewModel, onOpen, modifier)
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
            // Déclaré plus bas que celui d'EditorScreen, donc prioritaire : un premier retour ajuste l'image à l'écran.
            BackHandler(enabled = zoom.isZoomed) { zoom.reset() }
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
                        val label = stringResource(R.string.rendering)
                        LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = label })
                    }
                }
                Text(
                    stringResource(R.string.hint_edit),
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
        horizontalArrangement = Arrangement.spacedBy(dimens.spacing, Alignment.End),
    ) {
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
            // Sens inversé : vers la droite, le seuil baisse et plus de visages sont détectés.
            val mirror = FaceBlurUiState.MIN_THRESHOLD + FaceBlurUiState.MAX_THRESHOLD
            LabeledSlider(
                label = stringResource(R.string.threshold_label, state.threshold),
                value = mirror - state.threshold,
                onValueChange = { viewModel.setThreshold(mirror - it) },
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
                        range = MIN_TILE_PERCENT..MAX_TILE_PERCENT step TILE_PERCENT_STEP,
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
private fun IntSlider(label: String, value: Int, range: IntProgression, onValueChange: (Int) -> Unit, enabled: Boolean) {
    LabeledSlider(
        label = label,
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        range = range.first.toFloat()..range.last.toFloat(),
        enabled = enabled,
        steps = (range.last - range.first) / range.step - 1,
    )
}

