package com.joeysretro.joey64.launcher.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.joeysretro.joey64.launcher.library.FoundRom
import com.joeysretro.joey64.launcher.library.KnownFile
import com.joeysretro.joey64.launcher.library.RomInfo

/**
 * The library's tables (plan, Phase 0: SQLite for games, hashes, play time, collections and the
 * achievement progress cache). Kept apart from [LibraryDb] as plain SQL, checked against SQLite
 * 3.22, the oldest Joey64 runs on (Android 10): no upsert, no window functions, no STRICT tables.
 *
 * A game is the ROM itself, identified by its hash, not by where its file is: play time,
 * collections and achievements stay with the game when its folder is picked again (new URIs),
 * the file is renamed or moved, or the same ROM sits in two folders.
 */
object LibrarySchema {
    const val VERSION = 1

    val V1 = listOf(
        // md5: RetroAchievements' N64 hash, the MD5 of the ROM in big-endian (.z64) byte order.
        // crc32 of the same bytes is what No-Intro (and libretro-database) list.
        """
        CREATE TABLE games (
            id INTEGER PRIMARY KEY,
            md5 TEXT NOT NULL UNIQUE,
            crc32 TEXT NOT NULL,
            header_id TEXT,
            title TEXT NOT NULL,
            added_at INTEGER NOT NULL,
            last_played_at INTEGER,
            play_seconds INTEGER NOT NULL DEFAULT 0,
            favorite INTEGER NOT NULL DEFAULT 0
        )
        """,
        "CREATE INDEX games_crc32 ON games(crc32)",
        // Every ROM file found in a picked folder. game_id is set once the file is hashed; size and
        // modified say when it must be hashed again.
        """
        CREATE TABLE files (
            id INTEGER PRIMARY KEY,
            uri TEXT NOT NULL UNIQUE,
            tree_uri TEXT NOT NULL,
            name TEXT NOT NULL,
            size INTEGER NOT NULL,
            modified INTEGER NOT NULL,
            game_id INTEGER REFERENCES games(id) ON DELETE SET NULL,
            seen_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX files_game ON files(game_id)",
        "CREATE INDEX files_tree ON files(tree_uri)",
        // One row per time a game was played; games.play_seconds is their sum, kept in step.
        """
        CREATE TABLE play_sessions (
            id INTEGER PRIMARY KEY,
            game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
            started_at INTEGER NOT NULL,
            seconds INTEGER NOT NULL
        )
        """,
        "CREATE INDEX play_sessions_game ON play_sessions(game_id, started_at)",
        """
        CREATE TABLE collections (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            position INTEGER NOT NULL,
            created_at INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE collection_games (
            collection_id INTEGER NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
            game_id INTEGER NOT NULL REFERENCES games(id) ON DELETE CASCADE,
            position INTEGER NOT NULL,
            PRIMARY KEY (collection_id, game_id)
        )
        """,
        "CREATE INDEX collection_games_game ON collection_games(game_id)",
        // Achievement progress cache for the signed-in user, so the library and a game's page show
        // progress offline. Cleared on sign-out. Softcore and hardcore kept apart.
        """
        CREATE TABLE ra_sets (
            game_id INTEGER PRIMARY KEY REFERENCES games(id) ON DELETE CASCADE,
            ra_game_id INTEGER NOT NULL,
            title TEXT,
            achievements INTEGER NOT NULL,
            points INTEGER NOT NULL,
            earned INTEGER NOT NULL,
            earned_hardcore INTEGER NOT NULL,
            fetched_at INTEGER NOT NULL
        )
        """,
        "CREATE INDEX ra_sets_ra_game ON ra_sets(ra_game_id)",
        """
        CREATE TABLE ra_achievements (
            ra_game_id INTEGER NOT NULL,
            id INTEGER NOT NULL,
            title TEXT NOT NULL,
            description TEXT NOT NULL,
            points INTEGER NOT NULL,
            badge TEXT,
            position INTEGER NOT NULL,
            earned_at INTEGER,
            earned_hardcore_at INTEGER,
            PRIMARY KEY (ra_game_id, id)
        )
        """,
    )

    /** Tables the cache owns; emptied on sign-out. */
    val RA_CACHE = listOf("ra_achievements", "ra_sets")
}

/**
 * The launcher's database. One instance per process ([get]); SQLiteOpenHelper serialises access.
 * Write-ahead logging, so the library can read while a scan writes, and foreign keys on (SQLite
 * leaves them off by default).
 */
class LibraryDb private constructor(context: Context) :
    SQLiteOpenHelper(context, NAME, null, LibrarySchema.VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (sql in LibrarySchema.V1) db.execSQL(sql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first. Each later version adds a step here; nothing is ever dropped
        // and rebuilt, since play time and collections can't be scanned back.
    }

    /** Adds a finished play session and keeps the game's total and last-played in step. */
    fun recordPlay(gameId: Long, startedAt: Long, seconds: Long) {
        if (seconds <= 0) return
        writableDatabase.transaction {
            insertOrThrow("play_sessions", null, ContentValues().apply {
                put("game_id", gameId)
                put("started_at", startedAt)
                put("seconds", seconds)
            })
            execSQL(
                "UPDATE games SET play_seconds = play_seconds + ?, last_played_at = MAX(IFNULL(last_played_at, 0), ?) WHERE id = ?",
                arrayOf<Any>(seconds, startedAt + seconds * 1000, gameId),
            )
        }
    }

    /** Every file the library knows, for the scan to compare against (ScanPlanner). */
    fun knownFiles(): List<KnownFile> =
        readableDatabase.rawQuery("SELECT uri, tree_uri, size, modified, game_id FROM files", null).use { c ->
            buildList {
                while (c.moveToNext()) add(KnownFile(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3), if (c.isNull(4)) null else c.getLong(4)))
            }
        }

    /**
     * Records a file the scan read: its game, added by MD5 when the library hasn't seen it (any
     * copy of a game, zipped or not, in any byte order, is the same game), then the file's row.
     * [info] null = not an N64 ROM: the file is still recorded, so it isn't read again until it
     * changes. Returns the game's id. (No UPSERT: Android 10 ships SQLite 3.22.)
     */
    fun saveRead(file: FoundRom, info: RomInfo?, title: String, now: Long): Long? {
        var gameId: Long? = null
        writableDatabase.transaction {
            if (info != null) {
                insertWithOnConflict("games", null, ContentValues().apply {
                    put("md5", info.md5)
                    put("crc32", info.crc32)
                    put("header_id", info.header.gameCode)
                    put("title", title)
                    put("added_at", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                gameId = rawQuery("SELECT id FROM games WHERE md5 = ?", arrayOf(info.md5)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }
            val row = ContentValues().apply {
                put("uri", file.uri)
                put("tree_uri", file.treeUri)
                put("name", file.name)
                put("size", file.size)
                put("modified", file.modified)
                put("game_id", gameId)
                put("seen_at", now)
            }
            if (update("files", row, "uri = ?", arrayOf(file.uri)) == 0) insertOrThrow("files", null, row)
        }
        return gameId
    }

    fun markSeen(uris: Collection<String>, now: Long) {
        if (uris.isEmpty()) return
        writableDatabase.transaction {
            for (u in uris) execSQL("UPDATE files SET seen_at = ? WHERE uri = ?", arrayOf<Any>(now, u))
        }
    }

    /** Files that left their folder. Their games stay (play time and collections can't be scanned back); the library lists games with a file. */
    fun forgetFiles(uris: Collection<String>) {
        if (uris.isEmpty()) return
        writableDatabase.transaction {
            for (u in uris) delete("files", "uri = ?", arrayOf(u))
        }
    }

    /** Games with at least one file in a ROM folder: the library. */
    fun gameCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT game_id) FROM files WHERE game_id IS NOT NULL", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /** Sign-out: the cached progress belonged to that account. */
    fun clearAchievementCache() {
        writableDatabase.transaction {
            for (t in LibrarySchema.RA_CACHE) delete(t, null, null)
        }
    }

    companion object {
        const val NAME = "library.db"

        @Volatile private var instance: LibraryDb? = null

        fun get(context: Context): LibraryDb =
            instance ?: synchronized(this) { instance ?: LibraryDb(context.applicationContext).also { instance = it } }
    }
}
