// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.ui

import androidx.compose.runtime.saveable.Saver

sealed interface Destination {
    data object Editor : Destination
    data object Settings : Destination
    data object Credits : Destination
}

private val destinations = listOf(Destination.Editor, Destination.Settings, Destination.Credits)
val BackStackSaver: Saver<List<Destination>, List<Int>> = Saver(
    save = { stack -> stack.map(destinations::indexOf) },
    restore = { saved ->
        saved.mapNotNull(destinations::getOrNull).ifEmpty { listOf(Destination.Editor) }
    },
)
