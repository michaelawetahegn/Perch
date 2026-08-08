package dev.mkiros.perch.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.mkiros.perch.data.db.entity.EntryEntity
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.db.entity.FolderEntity

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
    entities = [FolderEntity::class, FeedEntity::class, EntryEntity::class],
    version = PerchDatabase.VERSION,
    exportSchema = true,
)
abstract class PerchDatabase : RoomDatabase() {

    abstract fun folderDao(): FolderDao

    abstract fun feedDao(): FeedDao

    abstract fun entryDao(): EntryDao

    companion object {
        const val NAME = "perch.db"

        /** Bumping this requires a [MIGRATIONS] entry from `VERSION - 1`. */
        const val VERSION = 2

        /**
         * Folders (U03). Creates the table, seeds Uncategorized as id 1, and files every
         * existing source under it.
         *
         * `feeds` is altered rather than rebuilt. Adding a `NOT NULL DEFAULT 1 REFERENCES`
         * column is legal here because Room enables foreign keys in `onOpen`, which runs
         * *after* migrations — and the alternative, the copy-drop-rename dance, would put
         * `DROP TABLE feeds` in the path of an `ON DELETE CASCADE` from `entries`. If
         * enforcement were ever on at this point, this statement fails loudly and writes
         * nothing, where the rebuild would take every article on the phone with it.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `sortIndex` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_name` " +
                        "ON `folders` (`name`)",
                )
                seedUncategorized(db)
                db.execSQL(
                    "ALTER TABLE `feeds` ADD COLUMN `folderId` INTEGER NOT NULL " +
                        "DEFAULT ${FolderEntity.UNCATEGORIZED_ID} " +
                        "REFERENCES `folders`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_feeds_folderId` ON `feeds` (`folderId`)",
                )
            }
        }

        /** Every migration the app has ever shipped, in order. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /**
         * Puts Uncategorized in place on a fresh install, so that "every source belongs to
         * exactly one folder" holds from the first row written rather than from the first
         * time the drawer is opened.
         */
        private val SEED_UNCATEGORIZED = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = seedUncategorized(db)
        }

        private fun seedUncategorized(db: SupportSQLiteDatabase) = db.execSQL(
            "INSERT OR IGNORE INTO `folders` (`id`, `name`, `sortIndex`, `createdAt`) " +
                "VALUES (${FolderEntity.UNCATEGORIZED_ID}, '${FolderEntity.UNCATEGORIZED_NAME}', " +
                "0, CAST(strftime('%s', 'now') AS INTEGER) * 1000)",
        )

        fun build(context: Context): PerchDatabase =
            Room.databaseBuilder(context.applicationContext, PerchDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .addCallback(SEED_UNCATEGORIZED)
                .build()

        /**
         * The database a test gets. It exists so that no test can accidentally build one
         * without [SEED_UNCATEGORIZED] — a database with no Uncategorized row rejects the
         * first feed inserted into it, which is a confusing way to learn about a callback.
         */
        fun inMemory(context: Context, allowMainThreadQueries: Boolean = true): PerchDatabase =
            Room.inMemoryDatabaseBuilder(context, PerchDatabase::class.java)
                .addCallback(SEED_UNCATEGORIZED)
                .apply { if (allowMainThreadQueries) allowMainThreadQueries() }
                .build()
    }
}
