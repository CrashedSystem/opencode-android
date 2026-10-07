@file:PortedFrom("OpenCodeTests/InlinePhotoSelectionTests.swift", "553a407")

package ai.opencode.mobilecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlinePhotoSelectionTests {
    private val first = PhotosPickerItem("first")
    private val second = PhotosPickerItem("second")

    @Test
    fun picksGetStableIDsInTapOrder() {
        val selection = InlinePhotoSelection()
        selection.items = listOf(first)
        val added = selection.reconcile().added
        selection.items = listOf(first, second)
        val change = selection.reconcile()
        assertEquals(listOf(first), added.map { it.item })
        assertEquals(listOf(second), change.added.map { it.item })
        assertTrue(change.removed.isEmpty())
        assertEquals(listOf(added[0].id, change.added[0].id), selection.selectedImages.map { it.id })
    }

    @Test
    fun deselectingReportsTheAttachmentToRemove() {
        val selection = InlinePhotoSelection()
        selection.items = listOf(first, second)
        val ids = selection.reconcile().added.map { it.id }
        selection.items = listOf(second)
        val change = selection.reconcile()
        assertTrue(change.added.isEmpty())
        assertEquals(listOf(ids[0]), change.removed)
    }

    @Test
    fun removingALandedChipDeselectsItsItem() {
        val selection = InlinePhotoSelection()
        selection.items = listOf(first, second)
        val ids = selection.reconcile().added.map { it.id }
        assertTrue(selection.attachmentsChanged(ids.toSet()).isEmpty())
        val orphans = selection.attachmentsChanged(setOf(ids[1]))
        assertTrue(orphans.isEmpty())
        assertEquals(listOf(second), selection.items)
        assertEquals(listOf(ids[0]), selection.reconcile().removed)
    }

    @Test
    fun anItemStillImportingStaysSelected() {
        val selection = InlinePhotoSelection()
        selection.items = listOf(first)
        selection.reconcile()
        assertTrue(selection.attachmentsChanged(emptySet()).isEmpty())
        assertEquals(listOf(first), selection.items)
    }

    @Test
    fun anAttachmentLandingAfterItsDeselectionIsAnOrphan() {
        val selection = InlinePhotoSelection()
        selection.items = listOf(first)
        val id = selection.reconcile().added[0].id
        selection.items = emptyList()
        selection.reconcile()
        assertEquals(listOf(id), selection.attachmentsChanged(setOf(id)))
        assertTrue(selection.attachmentsChanged(setOf(id)).isEmpty())
    }

    @Test
    fun sendingClearsTheSelection() {
        val selection = InlinePhotoSelection()
        selection.items = listOf(first, second)
        val ids = selection.reconcile().added.map { it.id }
        selection.attachmentsChanged(ids.toSet())
        selection.attachmentsChanged(emptySet())
        assertTrue(selection.items.isEmpty())
    }

    @Test
    fun failedImportsAreDeselected() {
        val selection = InlinePhotoSelection()
        selection.items = listOf(first, second)
        val ids = selection.reconcile().added.map { it.id }
        selection.drop(setOf(ids[1]))
        assertEquals(listOf(first), selection.items)
    }
}
