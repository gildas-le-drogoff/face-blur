// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.content.edit
import fr.gildasledrogoff.faceblur.domain.BlurSettings
import fr.gildasledrogoff.faceblur.domain.BlurType
import fr.gildasledrogoff.faceblur.domain.ExportFormat
import fr.gildasledrogoff.faceblur.domain.ExportOptions
import fr.gildasledrogoff.faceblur.domain.MAX_BLUR_STRENGTH
import fr.gildasledrogoff.faceblur.domain.MAX_TILE_PERCENT
import fr.gildasledrogoff.faceblur.domain.MIN_BLUR_STRENGTH
import fr.gildasledrogoff.faceblur.domain.MIN_TILE_PERCENT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

enum class ThemeMode { SYSTEM, LIGHT, DARK }
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val export: ExportOptions = ExportOptions(),
    val blur: BlurSettings = BlurSettings(),
    val drawBoxes: Boolean = false,
    val onboardingDone: Boolean = false,
)

class SettingsRepository private constructor(context: Context) {
    val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    fun setThemeMode(value: ThemeMode) = write { it.copy(themeMode = value) }
    fun setExportFormat(value: ExportFormat) =
        write { it.copy(export = it.export.copy(format = value)) }

    fun setKeepMetadata(value: Boolean) =
        write { it.copy(export = it.export.copy(keepMetadata = value)) }

    fun setBlurType(value: BlurType) = write { it.copy(blur = it.blur.copy(type = value)) }
    fun setBlurStrength(value: Int) = write { it.copy(blur = it.blur.copy(strength = value)) }
    fun setTilePercent(value: Int) = write { it.copy(blur = it.blur.copy(tilePercent = value)) }
    fun setDrawBoxes(value: Boolean) = write { it.copy(drawBoxes = value) }
    fun completeOnboarding() = write { it.copy(onboardingDone = true) }
    private fun write(transform: (AppSettings) -> AppSettings) {
        val s = _settings.updateAndGet(transform)
        prefs.edit {
            putString(KEY_THEME, s.themeMode.name)
            putString(KEY_FORMAT, s.export.format.name)
            putBoolean(KEY_KEEP_METADATA, s.export.keepMetadata)
            putString(KEY_BLUR_TYPE, s.blur.type.name)
            putInt(KEY_BLUR_STRENGTH, s.blur.strength)
            putInt(KEY_TILE_PERCENT, s.blur.tilePercent)
            putBoolean(KEY_DRAW_BOXES, s.drawBoxes)
            putBoolean(KEY_ONBOARDING_DONE, s.onboardingDone)
        }
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = prefs.getString(KEY_THEME, null).toEnum(defaults.themeMode),
            export = ExportOptions(
                format = prefs.getString(KEY_FORMAT, null).toEnum(defaults.export.format),
                keepMetadata = prefs.getBoolean(KEY_KEEP_METADATA, defaults.export.keepMetadata),
            ),
            blur = BlurSettings(
                type = prefs.getString(KEY_BLUR_TYPE, null).toEnum(defaults.blur.type),
                strength = prefs.getInt(KEY_BLUR_STRENGTH, defaults.blur.strength)
                    .coerceIn(MIN_BLUR_STRENGTH, MAX_BLUR_STRENGTH),
                tilePercent = prefs.getInt(KEY_TILE_PERCENT, defaults.blur.tilePercent)
                    .coerceIn(MIN_TILE_PERCENT, MAX_TILE_PERCENT),
            ),
            drawBoxes = isDebuggable && prefs.getBoolean(KEY_DRAW_BOXES, defaults.drawBoxes),
            onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, defaults.onboardingDone),
        )
    }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_FORMAT = "export_format"
        private const val KEY_KEEP_METADATA = "keep_metadata"
        private const val KEY_BLUR_TYPE = "blur_type"
        private const val KEY_BLUR_STRENGTH = "blur_strength"
        private const val KEY_TILE_PERCENT = "tile_percent"
        private const val KEY_DRAW_BOXES = "draw_boxes"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"

        @Volatile
        private var instance: SettingsRepository? = null
        fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: SettingsRepository(context.applicationContext).also { instance = it }
        }
    }
}

private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
    enumValues<E>().firstOrNull { it.name == this } ?: default
