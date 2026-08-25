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
import dev.mkiros.perch.data.db.entity.PendingEntryStateEntity

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
    entities = [
        FolderEntity::class,
        FeedEntity::class,
        EntryEntity::class,
        PendingEntryStateEntity::class,
    ],
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
        const val VERSION = 6

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

        /**
         * Read later and liked (U04). Adds the two timestamps that make those lists
         * orderable, and the indices the two destinations select on.
         *
         * The one non-mechanical statement is the `starredAt` backfill. `isStarred` has
         * existed since v1 with no UI to set it, so in practice no install has a starred
         * row — but a row that was somehow starred would arrive with a null `starredAt`
         * and sort off the end of the Liked list permanently. `fetchedAt` is the only
         * timestamp on hand that relates to when the reader could have seen it, and a
         * defensible position beats an invisible one.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `entries` ADD COLUMN `isSaved` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `savedAt` INTEGER")
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `starredAt` INTEGER")
                db.execSQL(
                    "UPDATE `entries` SET `starredAt` = `fetchedAt` " +
                        "WHERE `isStarred` = 1 AND `starredAt` IS NULL",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_entries_isSaved` ON `entries` (`isSaved`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_entries_isStarred` " +
                        "ON `entries` (`isStarred`)",
                )
            }
        }

        /**
         * Full text (U10). Two additive columns and no backfill, because both defaults are
         * true of every row already on the phone: nothing has been extracted yet, and the
         * excerpt flag is a fact the parser will supply on the next refresh anyway. Marking
         * old rows as excerpts on a guess would send Perch off to fetch 42 sites' worth of
         * pages the first time the reader opened anything.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `entries` ADD COLUMN `bodyIsExcerpt` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE `entries` ADD COLUMN `fullTextAt` INTEGER")
            }
        }

        /**
         * Profile restore (U14). One new table and not a single statement against an
         * existing one — an upgrade has nothing to park, so the table arrives empty and
         * the phone's articles and reader state are never read, let alone written.
         *
         * No foreign key to `feeds`. The whole purpose of a row here is to survive the
         * window in which its source may not exist yet, and a constraint would reject
         * exactly the rows worth keeping.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_entry_state` (" +
                        "`feedUrl` TEXT NOT NULL, `guid` TEXT NOT NULL, " +
                        "`isRead` INTEGER NOT NULL, `readAt` INTEGER, " +
                        "`isSaved` INTEGER NOT NULL, `savedAt` INTEGER, " +
                        "`isStarred` INTEGER NOT NULL, `starredAt` INTEGER, " +
                        "PRIMARY KEY(`feedUrl`, `guid`))",
                )
            }
        }

        /**
         * Saved links (Y02). One additive column and one seeded row: a pasted link needs a
         * feed to satisfy `entries.feedId`'s foreign key, and PLAN-6 §0.3 chose a synthetic
         * source over a nullable column. `INSERT OR IGNORE` against the unique `feedUrl`
         * index is what makes [seedSavedLinks] safe to also run from [SEED_SAVED_LINKS] on
         * a fresh install, exactly as [seedUncategorized] already does for folder 1.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `feeds` ADD COLUMN `isSynthetic` INTEGER NOT NULL DEFAULT 0",
                )
                seedSavedLinks(db)
            }
        }

        /** Every migration the app has ever shipped, in order. */
        val MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        /**
         * Puts Uncategorized in place on a fresh install, so that "every source belongs to
         * exactly one folder" holds from the first row written rather than from the first
         * time the drawer is opened.
         */
        private val SEED_UNCATEGORIZED = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = seedUncategorized(db)
        }

        /** The fresh-install twin of [MIGRATION_5_6]'s [seedSavedLinks] call. */
        private val SEED_SAVED_LINKS = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = seedSavedLinks(db)
        }

        private fun seedUncategorized(db: SupportSQLiteDatabase) = db.execSQL(
            "INSERT OR IGNORE INTO `folders` (`id`, `name`, `sortIndex`, `createdAt`) " +
                "VALUES (${FolderEntity.UNCATEGORIZED_ID}, '${FolderEntity.UNCATEGORIZED_NAME}', " +
                "0, CAST(strftime('%s', 'now') AS INTEGER) * 1000)",
        )

        private fun seedSavedLinks(db: SupportSQLiteDatabase) = db.execSQL(
            "INSERT OR IGNORE INTO `feeds` (`feedUrl`, `siteUrl`, `title`, `customTitle`, " +
                "`faviconUrl`, `etag`, `lastModified`, `lastFetchedAt`, `lastSuccessAt`, " +
                "`lastError`, `consecutiveFailures`, `addedAt`, `sortIndex`, `folderId`, " +
                "`isSynthetic`) VALUES ('${FeedEntity.SAVED_LINKS_FEED_URL}', NULL, " +
                "'${FeedEntity.SAVED_LINKS_TITLE}', NULL, NULL, NULL, NULL, NULL, NULL, NULL, " +
                "0, CAST(strftime('%s', 'now') AS INTEGER) * 1000, 0, " +
                "${FolderEntity.UNCATEGORIZED_ID}, 1)",
        )

        fun build(context: Context): PerchDatabase =
            Room.databaseBuilder(context.applicationContext, PerchDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .addCallback(SEED_UNCATEGORIZED)
                .addCallback(SEED_SAVED_LINKS)
                .build()

        /**
         * The database a test gets. It exists so that no test can accidentally build one
         * without [SEED_UNCATEGORIZED] or [SEED_SAVED_LINKS] — a database missing either
         * row rejects the first feed inserted into it, or omits the saved-links source,
         * which are both confusing ways to learn about a callback.
         */
        fun inMemory(context: Context, allowMainThreadQueries: Boolean = true): PerchDatabase =
            Room.inMemoryDatabaseBuilder(context, PerchDatabase::class.java)
                .addCallback(SEED_UNCATEGORIZED)
                .addCallback(SEED_SAVED_LINKS)
                .apply { if (allowMainThreadQueries) allowMainThreadQueries() }
                .build()
    }
}
