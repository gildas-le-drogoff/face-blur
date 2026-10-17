// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.gildasledrogoff.faceblur.data.ThemeMode
import fr.gildasledrogoff.faceblur.ui.FaceBlurScreen
import fr.gildasledrogoff.faceblur.ui.FaceBlurViewModel
import fr.gildasledrogoff.faceblur.ui.OnboardingScreen
import fr.gildasledrogoff.faceblur.ui.SettingsViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: FaceBlurViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val imagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) viewModel.open(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FaceDetectionLog.verbose = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        enableEdgeToEdge()
        if (savedInstanceState == null) intent.incomingImageUri()?.let(viewModel::open)
        val onSaved = if (intent.action == Intent.ACTION_EDIT) ::returnEditedImage else null
        setContent {
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            val isDarkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(isDarkTheme) {
                val style =
                    SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isDarkTheme }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val context = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(
                        context
                    )

                isDarkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                if (settings.onboardingDone) {
                    FaceBlurScreen(viewModel, settingsViewModel, ::openPicker, onSaved)
                } else {
                    OnboardingScreen(onDone = settingsViewModel::completeOnboarding)
                }
            }
        }
    }

    private fun openPicker() {
        if (viewModel.state.value.busy == null) {
            imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_O -> true.also { openPicker() }
        KeyEvent.KEYCODE_S -> true.also { viewModel.save() }
        KeyEvent.KEYCODE_Z -> true.also {
            if (event?.isShiftPressed == true) viewModel.redo() else viewModel.undo()
        }

        KeyEvent.KEYCODE_Y -> true.also { viewModel.redo() }
        else -> super.onKeyShortcut(keyCode, event)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>?,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data?.add(
            KeyboardShortcutGroup(
                getString(R.string.app_name),
                listOf(
                    KeyboardShortcutInfo(
                        getString(R.string.action_open),
                        KeyEvent.KEYCODE_O,
                        KeyEvent.META_CTRL_ON
                    ),
                    KeyboardShortcutInfo(
                        getString(R.string.action_save),
                        KeyEvent.KEYCODE_S,
                        KeyEvent.META_CTRL_ON
                    ),
                    KeyboardShortcutInfo(
                        getString(R.string.action_undo),
                        KeyEvent.KEYCODE_Z,
                        KeyEvent.META_CTRL_ON
                    ),
                    KeyboardShortcutInfo(
                        getString(R.string.action_redo),
                        KeyEvent.KEYCODE_Z,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
                    ),
                ),
            ),
        )
    }

    private fun returnEditedImage(uri: Uri) {
        setResult(RESULT_OK, editResult(uri))
        finish()
    }
}
