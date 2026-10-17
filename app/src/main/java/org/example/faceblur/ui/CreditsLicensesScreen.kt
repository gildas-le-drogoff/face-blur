package org.example.faceblur.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.example.faceblur.FaceDetectionLog
import org.example.faceblur.R
import java.io.InputStream

private const val TAG = "Credits"

/** Licence du modèle embarqué dans les assets (voir YuNetDetector.MODEL). */
private const val YUNET_LICENSE_ASSET = "face_detection_yunet_2026may.LICENSE.txt"

/** Crédits et licences : application, modèle de détection, bibliothèques. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsLicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = LocalDimens.current
    val context = LocalContext.current

    val version = remember {
        runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0).versionName
            }
        }.getOrNull()
    }
    // Texte affiché = texte exact embarqué dans l'APK (asset du modèle, res raw pour Apache 2.0).
    val yunetLicense = rememberLoadedText { it.assets.open(YUNET_LICENSE_ASSET) }
    val apacheLicense = rememberLoadedText { it.resources.openRawResource(R.raw.license_apache_2_0) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.credits_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(dimens.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            version?.let {
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

            CreditsSection(R.string.credits_section_detection) {
                LicenseCard(
                    name = stringResource(R.string.credits_yunet_name),
                    subtitle = stringResource(R.string.credits_yunet_subtitle),
                    copyright = stringResource(R.string.credits_yunet_copyright),
                    licenseName = stringResource(R.string.license_mit_name),
                    licenseText = yunetLicense,
                )
            }

            CreditsSection(R.string.credits_section_libraries) {
                LicenseCard(
                    name = stringResource(R.string.credits_kotlin_name),
                    subtitle = stringResource(R.string.credits_kotlin_subtitle),
                    copyright = stringResource(R.string.credits_kotlin_copyright),
                    licenseName = stringResource(R.string.license_apache_name),
                    licenseText = apacheLicense,
                )
                LicenseCard(
                    name = stringResource(R.string.credits_androidx_name),
                    subtitle = stringResource(R.string.credits_androidx_subtitle),
                    copyright = stringResource(R.string.credits_androidx_copyright),
                    licenseName = stringResource(R.string.license_apache_name),
                    licenseText = apacheLicense,
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
        modifier = Modifier.padding(top = LocalDimens.current.spacing),
    )
    Column(verticalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) { content() }
}

/** Carte dépliable : composant, détenteur du droit d'auteur, licence et son texte intégral. */
@Composable
private fun LicenseCard(
    name: String,
    subtitle: String,
    copyright: String,
    licenseName: String,
    licenseText: String,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    OutlinedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        val dimens = LocalDimens.current
        Column(
            Modifier.padding(dimens.spacing),
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            Row(
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
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.rotate(chevron),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Text(licenseText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Lit un fichier de licence une seule fois (quelques Ko) ; chaîne vide si inaccessible. */
@Composable
private fun rememberLoadedText(open: (Context) -> InputStream): String {
    val context = LocalContext.current
    return remember {
        FaceDetectionLog.timed(TAG, "Reading license file") {
            runCatching { open(context).use { it.readBytes().decodeToString() } }
                .onFailure { FaceDetectionLog.w(TAG, it) { "License file unreadable: ${it.message}" } }
                .getOrDefault("")
        }
    }
}
