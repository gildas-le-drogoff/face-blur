// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.faceblur.R
import fr.gildasledrogoff.faceblur.data.ThemeMode
import fr.gildasledrogoff.faceblur.domain.ExportFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onCredits: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val dimens = LocalDimens.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_back),
                            stringResource(R.string.cd_back)
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .wrapContentWidth()
                .widthIn(max = dimens.contentMaxWidth)
                .padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            SectionTitle(R.string.settings_section_appearance)
            Text(
                stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.labelLarge
            )
            ChoiceRow(
                options = ThemeMode.entries,
                selected = settings.themeMode,
                label = {
                    when (it) {
                        ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                        ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                        ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                    }
                },
                onSelect = viewModel::setThemeMode,
                enabled = true,
            )
            SectionTitle(R.string.section_export)
            Text(
                stringResource(R.string.export_format),
                style = MaterialTheme.typography.labelLarge
            )
            ChoiceRow(
                options = ExportFormat.entries,
                selected = settings.export.format,
                label = {
                    when (it) {
                        ExportFormat.JPEG -> stringResource(R.string.format_jpeg)
                        ExportFormat.PNG -> stringResource(R.string.format_png)
                        ExportFormat.WEBP -> stringResource(R.string.format_webp)
                    }
                },
                onSelect = viewModel::setExportFormat,
                enabled = true,
            )
            SwitchRow(
                text = stringResource(R.string.keep_metadata),
                checked = settings.export.keepMetadata,
                onCheckedChange = viewModel::setKeepMetadata,
                enabled = true,
                supporting = stringResource(
                    if (settings.export.keepMetadata) R.string.metadata_kept_hint else R.string.metadata_stripped_hint,
                ),
            )
            if (viewModel.isDebuggable) {
                SwitchRow(
                    text = stringResource(R.string.draw_boxes),
                    checked = settings.drawBoxes,
                    onCheckedChange = viewModel::setDrawBoxes,
                    enabled = true,
                )
            }
            SectionTitle(R.string.settings_section_about)
            DeveloperCard()
            OutlinedCard(onClick = onCredits, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(dimens.spacing),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.credits_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        painterResource(R.drawable.ic_expand_more),
                        contentDescription = null,
                        modifier = Modifier.rotate(-90f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
