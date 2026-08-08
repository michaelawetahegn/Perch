package dev.mkiros.perch.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A section of the reading list (PLAN-2 §0). Every source belongs to exactly one.
 *
 * [UNCATEGORIZED_ID] is a real row, not a null: making "no folder chosen" a folder means
 * the home screen has one sectioning rule instead of two, and deleting a folder has
 * somewhere to put its sources. It is seeded on create and by migration 1 → 2, and the
 * repository refuses to rename or delete it.
 *
 * @param sortIndex the user's order. Uncategorized is pinned last regardless of its own
 *   value — see [dev.mkiros.perch.data.db.FolderDao.observeAll].
 */
@Entity(tableName = "folders", indices = [Index(value = ["name"], unique = true)])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortIndex: Int = 0,
    val createdAt: Long,
) {
    companion object {
        /** The built-in folder. Undeletable, unrenameable, and always id 1. */
        const val UNCATEGORIZED_ID = 1L

        const val UNCATEGORIZED_NAME = "Uncategorized"
    }
}
