package dev.mkiros.perch.ui.home

import androidx.compose.runtime.saveable.Saver
import dev.mkiros.perch.data.db.entity.FolderEntity

/**
 * What the drawer has ticked (PLAN-2 U09a).
 *
 * A value rather than a pair of sets in the drawer, because the two rules worth stating
 * are invariants and a value can hold them:
 *
 * - **A selection is homogeneous.** One started on a source takes only sources, one
 *   started on a folder takes only folders. Deleting a mixed batch would make one verb
 *   mean two different things at once — folders reassign their sources and are undoable,
 *   sources cascade to their entries and are not — and no wording of a single button can
 *   describe both.
 * - **Uncategorized is never in one.** §0 makes it undeletable; it is where a deleted
 *   folder's sources go, so a batch that deleted it would have nowhere to put them.
 *
 * Unticking the last row leaves selection mode altogether rather than leaving a
 * contextual bar reading "0 selected" over a delete button that does nothing.
 */
sealed interface DrawerSelection {

    /** What is ticked. Empty only for [None] — see [toggleSource]. */
    val ids: Set<Long>

    /** Not in selection mode. The drawer draws no checkboxes and its header is its own. */
    data object None : DrawerSelection {
        override val ids: Set<Long> = emptySet()
    }

    /** Sources, by feed id. Deleting these cascades to their entries — see U09a. */
    data class Sources(override val ids: Set<Long>) : DrawerSelection

    /** Folders, by folder id, never including [FolderEntity.UNCATEGORIZED_ID]. */
    data class Folders(override val ids: Set<Long>) : DrawerSelection

    val count: Int get() = ids.size

    val isActive: Boolean get() = this != None

    fun holdsSource(feedId: Long): Boolean = this is Sources && feedId in ids

    fun holdsFolder(folderId: Long): Boolean = this is Folders && folderId in ids

    /**
     * The selection as something `rememberSaveable` can put in a `Bundle` — a flat list of
     * a discriminator and the ids. A selection that a rotation or a process death silently
     * dropped would leave the reader looking at a contextual bar counting rows that are no
     * longer ticked.
     */
    fun save(): List<Long> = when (this) {
        None -> listOf(KIND_NONE)
        is Sources -> listOf(KIND_SOURCES) + ids
        is Folders -> listOf(KIND_FOLDERS) + ids
    }

    companion object {
        private const val KIND_NONE = 0L
        private const val KIND_SOURCES = 1L
        private const val KIND_FOLDERS = 2L

        fun restore(saved: List<Long>): DrawerSelection {
            val ids = saved.drop(1).toSet()
            return when (saved.firstOrNull()) {
                KIND_SOURCES -> Sources(ids)
                KIND_FOLDERS -> Folders(ids)
                else -> None
            }
        }

        val Saver: Saver<DrawerSelection, List<Long>> =
            Saver(save = { it.save() }, restore = ::restore)
    }
}

/** Ticks or unticks one source, starting a source selection if there was none. */
fun DrawerSelection.toggleSource(feedId: Long): DrawerSelection = when (this) {
    DrawerSelection.None -> DrawerSelection.Sources(setOf(feedId))
    is DrawerSelection.Sources -> DrawerSelection.Sources(ids.toggle(feedId)).orNone()
    // Homogeneous: a folder selection ignores a source entirely rather than converting.
    is DrawerSelection.Folders -> this
}

/** Ticks or unticks one folder. Uncategorized is refused here, not merely hidden. */
fun DrawerSelection.toggleFolder(folderId: Long): DrawerSelection = when {
    folderId == FolderEntity.UNCATEGORIZED_ID -> this
    this == DrawerSelection.None -> DrawerSelection.Folders(setOf(folderId))
    this is DrawerSelection.Folders -> DrawerSelection.Folders(ids.toggle(folderId)).orNone()
    else -> this
}

/**
 * Whether the drawer must draw [folderId]'s header as unavailable (V10).
 *
 * Refusal is *defined* as "a tick would change nothing", so it cannot drift away from
 * [toggleFolder]: a source selection refuses every folder, a folder selection refuses
 * Uncategorized, and outside selection mode a header is not a tick target at all — it
 * scopes the list, which is always available.
 */
fun DrawerSelection.refusesFolder(folderId: Long): Boolean =
    isActive && toggleFolder(folderId) == this

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

private fun DrawerSelection.orNone(): DrawerSelection = if (count == 0) DrawerSelection.None else this
