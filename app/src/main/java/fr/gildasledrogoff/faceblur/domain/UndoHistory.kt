// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.domain

data class UndoHistory<T>(
    val past: List<T> = emptyList(),
    val future: List<T> = emptyList(),
    val limit: Int = DEFAULT_UNDO_LIMIT,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    fun record(previous: T): UndoHistory<T> =
        copy(past = (past + previous).takeLast(limit), future = emptyList())

    fun undo(current: T): Pair<T, UndoHistory<T>>? = past.lastOrNull()?.let {
        it to copy(past = past.dropLast(1), future = future + current)
    }

    fun redo(current: T): Pair<T, UndoHistory<T>>? = future.lastOrNull()?.let {
        it to copy(past = (past + current).takeLast(limit), future = future.dropLast(1))
    }
}

const val DEFAULT_UNDO_LIMIT = 100
