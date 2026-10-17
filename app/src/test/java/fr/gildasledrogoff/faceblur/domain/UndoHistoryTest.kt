// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.domain
import org.junit.Assert.*
import org.junit.Test
class UndoHistoryTest {
    @Test
    fun `undo and redo walk back and forth through recorded states`() {
        val history = UndoHistory<Int>().record(0).record(1)
        val (undone, afterUndo) = history.undo(2)!!
        assertEquals(1, undone)
        assertTrue(afterUndo.canRedo)
        val (redone, afterRedo) = afterUndo.redo(undone)!!
        assertEquals(2, redone)
        assertEquals(history, afterRedo)
    }
    @Test
    fun `nothing to undo or redo on an empty history`() {
        val empty = UndoHistory<Int>()
        assertFalse(empty.canUndo)
        assertFalse(empty.canRedo)
        assertNull(empty.undo(0))
        assertNull(empty.redo(0))
    }
    @Test
    fun `a new edit after an undo drops the redo branch`() {
        val (_, undone) = UndoHistory<Int>().record(0).undo(1)!!
        val edited = undone.record(0)
        assertFalse(edited.canRedo)
        assertEquals(listOf(0), edited.past)
    }
    @Test
    fun `history keeps only the most recent states`() {
        val history = (0 until 5).fold(UndoHistory<Int>(limit = 3)) { history, state -> history.record(state) }
        assertEquals(listOf(2, 3, 4), history.past)
    }
}
