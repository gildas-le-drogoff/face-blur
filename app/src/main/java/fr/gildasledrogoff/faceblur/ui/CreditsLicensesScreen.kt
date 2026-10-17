// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.gildasledrogoff.faceblur.FaceDetectionLog
import fr.gildasledrogoff.faceblur.R

private const val TAG = "Credits"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsLicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = LocalDimens.current
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            val packageManager = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        }.getOrNull()
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.credits_title)) },
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
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            versionName?.let {
                Text(
                    stringResource(R.string.credits_version, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.credits_app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CreditsSection(R.string.credits_section_developer) {
                DeveloperCard()
            }
            CreditsSection(R.string.credits_section_license) {
                LicenseCard(
                    name = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.credits_app_subtitle),
                    copyright = stringResource(R.string.credits_app_copyright),
                    licenseName = stringResource(R.string.license_gpl_name),
                )
            }
            CreditsSection(R.string.credits_section_detection) {
                LicenseCard(
                    name = stringResource(R.string.credits_yunet_name),
                    subtitle = stringResource(R.string.credits_yunet_subtitle),
                    copyright = stringResource(R.string.credits_yunet_copyright),
                    licenseName = stringResource(R.string.license_mit_name),
                )
            }
            CreditsSection(R.string.credits_section_libraries) {
                LicenseCard(
                    name = stringResource(R.string.credits_kotlin_name),
                    subtitle = stringResource(R.string.credits_kotlin_subtitle),
                    copyright = stringResource(R.string.credits_kotlin_copyright),
                    licenseName = stringResource(R.string.license_apache_name),
                )
                LicenseCard(
                    name = stringResource(R.string.credits_androidx_name),
                    subtitle = stringResource(R.string.credits_androidx_subtitle),
                    copyright = stringResource(R.string.credits_androidx_copyright),
                    licenseName = stringResource(R.string.license_apache_name),
                )
            }
        }
    }
}

@Composable
private fun CreditsSection(@StringRes title: Int, content: @Composable () -> Unit) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = LocalDimens.current.spacing)
            .semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) { content() }
}

@Composable
internal fun DeveloperCard() {
    val dimens = LocalDimens.current
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.credits_source_url)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(dimens.sectionSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            Image(
                painterResource(R.drawable.ic_lama),
                contentDescription = null,
                modifier = Modifier.size(72.dp),
            )
            Text(
                stringResource(R.string.credits_developer_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.credits_developer_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = {
                    runCatching { uriHandler.openUri(sourceUrl) }
                        .onFailure {
                            FaceDetectionLog.w(TAG, it) { "Cannot open $sourceUrl: ${it.message}" }
                        }
                },
                modifier = Modifier.padding(top = dimens.spacing),
            ) {
                Icon(
                    painterResource(R.drawable.ic_code),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.credits_source_code))
            }
            Text(
                sourceUrl.removePrefix("https://"),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LicenseCard(
    name: String,
    subtitle: String,
    copyright: String,
    licenseName: String,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        val dimens = LocalDimens.current
        Row(
            Modifier
                .fillMaxWidth()
                .padding(dimens.spacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    copyright,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                licenseName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
