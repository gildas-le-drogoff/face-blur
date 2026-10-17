// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import fr.gildasledrogoff.faceblur.data.AppSettings
import fr.gildasledrogoff.faceblur.data.SettingsRepository
import fr.gildasledrogoff.faceblur.data.ThemeMode
import fr.gildasledrogoff.faceblur.domain.ExportFormat
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SettingsRepository.get(app)
    val settings: StateFlow<AppSettings> = repository.settings
    val isDebuggable = repository.isDebuggable
    fun setThemeMode(value: ThemeMode) = repository.setThemeMode(value)
    fun setExportFormat(value: ExportFormat) = repository.setExportFormat(value)
    fun setKeepMetadata(value: Boolean) = repository.setKeepMetadata(value)
    fun setDrawBoxes(value: Boolean) = repository.setDrawBoxes(value)
    fun completeOnboarding() = repository.completeOnboarding()
}
