package dev.mkiros.perch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity

/**
 * The single local store. Every column in SPEC.md §4 is a SQLite-native type, so there
 * are no `@TypeConverter`s to declare — dates are epoch millis, not `Instant`.
 *
 * Schemas are exported to `app/schemas/`. Pre-1.0 the app destroys and recreates on a
 * version bump; T31 removes that and adds real migrations before the first daily-use
 * install.
 */
@Database(
    entities = [FeedEntity::class, EntryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PerchDatabase : RoomDatabase() {

    abstract fun feedDao(): FeedDao

    abstract fun entryDao(): EntryDao

    companion object {
        const val NAME = "perch.db"

        fun build(context: Context): PerchDatabase =
            Room.databaseBuilder(context.applicationContext, PerchDatabase::class.java, NAME)
                .fallbackToDestructiveMigration()
                .build()
    }
}
