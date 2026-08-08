package dev.mkiros.perch.ui.home

import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.entity.FolderEntity
import org.junit.Test

/**
 * U09a's selection rules, as a pure model rather than as drawer state.
 *
 * The two rules that actually bite are both invariants rather than behaviours — a
 * selection is **homogeneous**, and Uncategorized is never in one — so they are asserted
 * here, once, against a value; the drawer is then free to draw a checkbox wherever it
 * likes without being the thing that enforces them.
 */
class DrawerSelectionTest {

    @Test
    fun `nothing is selected until a long press starts a selection`() {
        assertThat(DrawerSelection.None.isActive).isFalse()
        assertThat(DrawerSelection.None.count).isEqualTo(0)
    }

    @Test
    fun `a long press on a source starts a selection holding that source`() {
        val selection = DrawerSelection.None.toggleSource(7)

        assertThat(selection.isActive).isTrue()
        assertThat(selection.holdsSource(7)).isTrue()
        assertThat(selection.count).isEqualTo(1)
    }

    @Test
    fun `tapping toggles a source in and back out of the selection`() {
        val two = DrawerSelection.None.toggleSource(7).toggleSource(8)
        assertThat(two.count).isEqualTo(2)

        val one = two.toggleSource(7)
        assertThat(one.holdsSource(7)).isFalse()
        assertThat(one.holdsSource(8)).isTrue()
    }

    @Test
    fun `unticking the last row leaves selection mode rather than showing a count of zero`() {
        val gone = DrawerSelection.None.toggleSource(7).toggleSource(7)

        assertThat(gone).isEqualTo(DrawerSelection.None)
    }

    @Test
    fun `a selection started on a source takes no folders`() {
        val sources = DrawerSelection.None.toggleSource(7)

        val unchanged = sources.toggleFolder(3)

        assertThat(unchanged).isEqualTo(sources)
        assertThat(unchanged.holdsFolder(3)).isFalse()
    }

    @Test
    fun `a selection started on a folder takes no sources`() {
        val folders = DrawerSelection.None.toggleFolder(3)

        val unchanged = folders.toggleSource(7)

        assertThat(unchanged).isEqualTo(folders)
        assertThat(unchanged.holdsSource(7)).isFalse()
    }

    @Test
    fun `Uncategorized cannot be selected — it is the folder delete has nowhere to move to`() {
        val started = DrawerSelection.None.toggleFolder(FolderEntity.UNCATEGORIZED_ID)
        assertThat(started).isEqualTo(DrawerSelection.None)

        val other = DrawerSelection.None.toggleFolder(3)
        assertThat(other.toggleFolder(FolderEntity.UNCATEGORIZED_ID)).isEqualTo(other)
    }

    @Test
    fun `a source selection survives being written out and read back`() {
        val selection = DrawerSelection.None.toggleSource(7).toggleSource(8)

        assertThat(DrawerSelection.restore(selection.save())).isEqualTo(selection)
    }

    @Test
    fun `a folder selection survives being written out and read back`() {
        val selection = DrawerSelection.None.toggleFolder(3)

        assertThat(DrawerSelection.restore(selection.save())).isEqualTo(selection)
    }

    @Test
    fun `an empty selection survives being written out and read back`() {
        assertThat(DrawerSelection.restore(DrawerSelection.None.save()))
            .isEqualTo(DrawerSelection.None)
    }
}
