package dev.mkiros.perch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity

/**
 * The single local store. Every column in SPEC.md §4 is a SQLite-native type, so there
 * are no `@TypeConverter`s to declare — dates are epoch millis, not `Instant`.
 *
 * Schemas are exported to `app/schemas/`. Version 1 is the shipped migration baseline:
 * there is no destructive fallback, so a version bump without a matching [MIGRATIONS]
 * entry is a crash on every existing install, not a silent wipe of the user's feeds.
 * `PerchDatabaseMigrationTest` fails the build before that can ship.
 */
@Database(
    entities = [FeedEntity::class, EntryEntity::class],
    version = PerchDatabase.VERSION,
    exportSchema = true,
)
abstract class PerchDatabase : RoomDatabase() {

    abstract fun feedDao(): FeedDao

    abstract fun entryDao(): EntryDao

    companion object {
        const val NAME = "perch.db"

        /** Shipped baseline. Bumping this requires a [MIGRATIONS] entry from `VERSION - 1`. */
        const val VERSION = 1

        /**
         * Every migration the app has ever shipped, in order. Empty at the baseline —
         * nothing has been released before version 1, so there is nothing to upgrade from.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): PerchDatabase =
            Room.databaseBuilder(context.applicationContext, PerchDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
