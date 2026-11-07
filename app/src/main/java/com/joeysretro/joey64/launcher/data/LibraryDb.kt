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
    const val VERSION = 5

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

    /**
     * Version 2 (Phase 2, box art): what each game shows. kind: "libretro" (a downloaded cover,
     * boxart/<md5>.jpg; cover = its libretro name), "none" (no cover found in the listing of
     * checked_at; looked for again when a newer listing comes), later "custom" and "hidden"
     * (Details, Change box art). A game without a row hasn't been looked up yet.
     */
    val V2 = listOf(
        """
        CREATE TABLE box_art (
            game_id INTEGER PRIMARY KEY REFERENCES games(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            cover TEXT,
            checked_at INTEGER NOT NULL
        )
        """,
    )

    /**
     * Version 3 (Details): what the ROM's header says and its size, for the Details popup. Set
     * by every read; for games read before, the upgrade has their files read again.
     */
    val V3 = listOf(
        "ALTER TABLE games ADD COLUMN region INTEGER",
        "ALTER TABLE games ADD COLUMN revision INTEGER",
        "ALTER TABLE games ADD COLUMN rom_size INTEGER",
    )

    /**
     * Version 4 (Phase 6, cheats): the header's checksums and country byte, "CRC1-CRC2-CC", the
     * key cheat lists are filed under (CheatLogic.keyOf). Set by every read; the upgrade has the
     * files read again, as V3's did.
     */
    val V4 = listOf(
        "ALTER TABLE games ADD COLUMN header_key TEXT",
    )

    /**
     * Version 5 (Phase 7, the progress cache, decided 2026-11-04): what rc_client said about the game
     * the last time it ran, kept whole (its game summary and its achievement list, as JSON), so
     * the Achievements pages show sets and last-session progress without the game running.
     */
    val V5 = listOf(
        "ALTER TABLE ra_sets ADD COLUMN badge TEXT",
        "ALTER TABLE ra_sets ADD COLUMN game_json TEXT",
        "ALTER TABLE ra_sets ADD COLUMN list_json TEXT",
    )

    /** Tables the cache owns; emptied on sign-out. */
    val RA_CACHE = listOf("ra_achievements", "ra_sets")
}

/**
 * A game in the library grid. [md5] is its RetroAchievements hash, and names its unpacked copy
 * and its cover. [art]: it has a cover to show (boxart/<md5>.jpg).
 */
data class LibraryGame(
    val id: Long, val title: String, val md5: String, val art: ArtKind? = null,
    /** CRC32 of the .z64 bytes, for the player-count table (filters). */
    val crc32: String = "",
    val played: Boolean = false,
    /** In Favourites (games.favorite). */
    val favourite: Boolean = false,
    /** The header's "CRC1-CRC2-CC", the cheat lists' key; null until the ROM is read again (V4). */
    val headerKey: String? = null,
)

/** A collection the user made (Phase 5). Favourites isn't one: it's games.favorite, always first. */
data class GameCollection(val id: Long, val name: String)

/** What a game's tile shows, from `box_art.kind` (V2). Null: no row yet, not looked up. */
enum class ArtKind(val db: String) {
    /** A downloaded libretro cover. */
    Libretro("libretro"),
    /** Looked up, nothing found: a title tile. */
    None("none"),
    /** A picture the user chose (Details, Change box art). */
    Custom("custom"),
    /** No box art, by the user's choice: a title tile, never looked up again. */
    Hidden("hidden");

    val shows get() = this == Libretro || this == Custom

    companion object {
        fun of(db: String?) = entries.firstOrNull { it.db == db }
    }
}

/** Everything the Details popup shows. [file] is the most recently seen file's name. */
data class GameDetails(
    val title: String,
    val md5: String,
    val file: String?,
    val regionCode: Char?,
    val revision: Int?,
    val romSize: Long?,
    val lastPlayedAt: Long?,
    val playSeconds: Long,
    val art: ArtKind?,
    /** The libretro cover found for it, kept when the user picks another, so Downloaded art can come back. */
    val cover: String?,
)

/** A game whose cover hasn't been looked up against the current listing: its first file's name, without extension. */
data class ArtLookup(val gameId: Long, val md5: String, val fileName: String)

/** One of a game's files in a picked ROM folder. */
data class GameFile(val uri: String, val treeUri: String, val name: String)

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
        for (sql in LibrarySchema.V1 + LibrarySchema.V2 + LibrarySchema.V3 + LibrarySchema.V4 + LibrarySchema.V5) db.execSQL(sql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first. Each later version adds a step here; nothing is ever dropped
        // and rebuilt, since play time and collections can't be scanned back.
        if (oldVersion < 2) for (sql in LibrarySchema.V2) db.execSQL(sql)
        if (oldVersion < 3) {
            for (sql in LibrarySchema.V3) db.execSQL(sql)
            // Read every file again (a size no file has), so the new columns fill in. Games, play
            // time and covers stay: they're keyed by MD5.
            db.execSQL("UPDATE files SET size = -1")
        }
        if (oldVersion < 4) {
            for (sql in LibrarySchema.V4) db.execSQL(sql)
            db.execSQL("UPDATE files SET size = -1")
        }
        if (oldVersion < 5) for (sql in LibrarySchema.V5) db.execSQL(sql)
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

    /**
     * The library's lists for Settings › Saves › Back up Joey64 (approved Saves mockup 2026-11-07): every
     * game by MD5 with its favourite mark and play times, and the collections by name. ROM files and box
     * art aren't in it: the scan finds them again.
     */
    fun exportLists(): org.json.JSONObject {
        val db = readableDatabase
        val games = org.json.JSONArray()
        db.rawQuery("SELECT md5, crc32, header_id, title, added_at, last_played_at, favorite FROM games", null).use { c ->
            while (c.moveToNext()) games.put(org.json.JSONObject().apply {
                put("md5", c.getString(0)); put("crc32", c.getString(1)); if (!c.isNull(2)) put("header_id", c.getString(2))
                put("title", c.getString(3)); put("added_at", c.getLong(4)); if (!c.isNull(5)) put("last_played_at", c.getLong(5))
                put("favorite", c.getInt(6) != 0)
            })
        }
        val sessions = org.json.JSONArray()
        db.rawQuery("SELECT g.md5, s.started_at, s.seconds FROM play_sessions s JOIN games g ON g.id = s.game_id", null).use { c ->
            while (c.moveToNext()) sessions.put(org.json.JSONArray().put(c.getString(0)).put(c.getLong(1)).put(c.getLong(2)))
        }
        val collections = org.json.JSONArray()
        db.rawQuery("SELECT id, name, position, created_at FROM collections ORDER BY position", null).use { c ->
            while (c.moveToNext()) {
                val list = org.json.JSONArray()
                db.rawQuery("SELECT g.md5 FROM collection_games cg JOIN games g ON g.id = cg.game_id WHERE cg.collection_id = ? ORDER BY cg.position",
                    arrayOf(c.getLong(0).toString())).use { g -> while (g.moveToNext()) list.put(g.getString(0)) }
                collections.put(org.json.JSONObject().apply { put("name", c.getString(1)); put("position", c.getInt(2)); put("created_at", c.getLong(3)); put("games", list) })
            }
        }
        return org.json.JSONObject().apply { put("games", games); put("sessions", sessions); put("collections", collections) }
    }

    /**
     * Brings back [exportLists]' lists: games missing here are added (they show once a ROM folder has
     * them), each game's favourite mark is the backup's, play sessions not here are added (totals worked
     * out again), and a collection of the same name gets the backup's games. Games not in the backup stay.
     */
    fun importLists(o: org.json.JSONObject, now: Long = System.currentTimeMillis()) {
        writableDatabase.transaction {
            fun idOf(md5: String) = rawQuery("SELECT id FROM games WHERE md5 = ?", arrayOf(md5)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            val games = o.optJSONArray("games") ?: org.json.JSONArray()
            for (i in 0 until games.length()) {
                val g = games.getJSONObject(i)
                val md5 = g.optString("md5").lowercase().takeIf { it.length == 32 } ?: continue
                insertWithOnConflict("games", null, ContentValues().apply {
                    put("md5", md5); put("crc32", g.optString("crc32")); if (g.has("header_id")) put("header_id", g.optString("header_id"))
                    put("title", g.optString("title", md5)); put("added_at", g.optLong("added_at", now))
                }, SQLiteDatabase.CONFLICT_IGNORE)
                update("games", ContentValues().apply { put("favorite", if (g.optBoolean("favorite")) 1 else 0) }, "md5 = ?", arrayOf(md5))
            }
            val touched = mutableSetOf<Long>()
            val sessions = o.optJSONArray("sessions") ?: org.json.JSONArray()
            for (i in 0 until sessions.length()) {
                val s = sessions.optJSONArray(i) ?: continue
                val id = idOf(s.optString(0).lowercase()) ?: continue
                val at = s.optLong(1); val sec = s.optLong(2)
                val have = rawQuery("SELECT 1 FROM play_sessions WHERE game_id = ? AND started_at = ?", arrayOf(id.toString(), at.toString())).use { it.moveToFirst() }
                if (!have && sec > 0) {
                    insertOrThrow("play_sessions", null, ContentValues().apply { put("game_id", id); put("started_at", at); put("seconds", sec) })
                    touched += id
                }
            }
            for (id in touched) execSQL(
                "UPDATE games SET play_seconds = (SELECT IFNULL(SUM(seconds), 0) FROM play_sessions WHERE game_id = ?1)," +
                    " last_played_at = MAX(IFNULL(last_played_at, 0), (SELECT IFNULL(MAX(started_at + seconds * 1000), 0) FROM play_sessions WHERE game_id = ?1)) WHERE id = ?1",
                arrayOf<Any>(id))
            val collections = o.optJSONArray("collections") ?: org.json.JSONArray()
            for (i in 0 until collections.length()) {
                val c = collections.getJSONObject(i)
                val name = c.optString("name").takeIf { it.isNotBlank() } ?: continue
                var cid = rawQuery("SELECT id FROM collections WHERE name = ?", arrayOf(name)).use { r -> if (r.moveToFirst()) r.getLong(0) else null }
                if (cid == null) {
                    val pos = rawQuery("SELECT IFNULL(MAX(position), -1) + 1 FROM collections", null).use { r -> r.moveToFirst(); r.getInt(0) }
                    cid = insertOrThrow("collections", null, ContentValues().apply { put("name", name); put("position", pos); put("created_at", c.optLong("created_at", now)) })
                }
                delete("collection_games", "collection_id = ?", arrayOf(cid.toString()))
                val list = c.optJSONArray("games") ?: org.json.JSONArray()
                var p = 0
                for (k in 0 until list.length()) {
                    val id = idOf(list.optString(k).lowercase()) ?: continue
                    insertWithOnConflict("collection_games", null, ContentValues().apply { put("collection_id", cid); put("game_id", id); put("position", p++) }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
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
                update("games", ContentValues().apply {
                    put("region", info.header.region.code.code)
                    put("revision", info.header.revision)
                    put("rom_size", info.size)
                    put("header_key", com.joeysretro.joey64.cheats.CheatLogic.keyOf(info.header))
                }, "md5 = ?", arrayOf(info.md5))
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

    /** The library: every game with at least one file in a ROM folder, by title. */
    fun libraryGames(): List<LibraryGame> =
        readableDatabase.rawQuery(
            "SELECT g.id, g.title, g.md5, a.kind, g.crc32, g.last_played_at IS NOT NULL, g.favorite, g.header_key FROM games g LEFT JOIN box_art a ON a.game_id = g.id" +
                " WHERE EXISTS (SELECT 1 FROM files f WHERE f.game_id = g.id) ORDER BY g.title COLLATE NOCASE",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(LibraryGame(c.getLong(0), c.getString(1), c.getString(2), ArtKind.of(c.getString(3)),
                    crc32 = c.getString(4), played = c.getInt(5) != 0, favourite = c.getInt(6) != 0, headerKey = c.getString(7)))
            }
        }

    // ---- Collections (Phase 5; tables from V1) -----------------------------------------------------

    /** The user's collections, oldest first (Favourites isn't one; see [setFavourite]). */
    fun collections(): List<GameCollection> =
        readableDatabase.rawQuery("SELECT id, name FROM collections ORDER BY position, id", null).use { c ->
            buildList { while (c.moveToNext()) add(GameCollection(c.getLong(0), c.getString(1))) }
        }

    /** Which games each collection holds: collection id to game ids. */
    fun collectionGames(): Map<Long, Set<Long>> =
        readableDatabase.rawQuery("SELECT collection_id, game_id FROM collection_games", null).use { c ->
            val m = HashMap<Long, MutableSet<Long>>()
            while (c.moveToNext()) m.getOrPut(c.getLong(0)) { HashSet() }.add(c.getLong(1))
            m
        }

    fun setFavourite(gameId: Long, on: Boolean) {
        writableDatabase.execSQL("UPDATE games SET favorite = ? WHERE id = ?", arrayOf<Any>(if (on) 1 else 0, gameId))
    }

    /** Puts [gameId] in collection [collectionId], or takes it out. */
    fun setInCollection(collectionId: Long, gameId: Long, on: Boolean) {
        writableDatabase.transaction {
            if (on) insertWithOnConflict("collection_games", null, ContentValues().apply {
                put("collection_id", collectionId)
                put("game_id", gameId)
                put("position", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_IGNORE)
            else delete("collection_games", "collection_id = ? AND game_id = ?", arrayOf(collectionId.toString(), gameId.toString()))
        }
    }

    /** A name already in use, ignoring case and the spaces around it; Favourites is taken too. [except]: renaming that one. */
    fun nameTaken(name: String, except: Long? = null): Boolean {
        val n = name.trim()
        if (n.equals(FAVOURITES, ignoreCase = true)) return true
        return collections().any { it.id != except && it.name.equals(n, ignoreCase = true) }
    }

    /** A new collection named [name] (trimmed), last in the list; null when the name is taken. */
    fun newCollection(name: String, now: Long = System.currentTimeMillis()): Long? {
        if (name.isBlank() || nameTaken(name)) return null
        return writableDatabase.insertOrThrow("collections", null, ContentValues().apply {
            put("name", name.trim())
            put("position", now)
            put("created_at", now)
        })
    }

    fun renameCollection(id: Long, name: String): Boolean {
        if (name.isBlank() || nameTaken(name, except = id)) return false
        writableDatabase.update("collections", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id.toString()))
        return true
    }

    /** Deletes the collection only; its games stay in the library. */
    fun deleteCollection(id: Long) {
        writableDatabase.delete("collections", "id = ?", arrayOf(id.toString()))
    }

    /** The library game played last, for Continue; null before anything has been played. */
    fun lastPlayed(): LibraryGame? =
        readableDatabase.rawQuery(
            "SELECT g.id, g.title, g.md5, a.kind FROM games g LEFT JOIN box_art a ON a.game_id = g.id" +
                " WHERE g.last_played_at IS NOT NULL AND EXISTS (SELECT 1 FROM files f WHERE f.game_id = g.id)" +
                " ORDER BY g.last_played_at DESC LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) LibraryGame(c.getLong(0), c.getString(1), c.getString(2), ArtKind.of(c.getString(3))) else null }

    /** The library game with a file whose [nameOf] (its save name) is [saveName]: whose recovery state it is. */
    fun gameWithFile(saveName: String, nameOf: (String) -> String): LibraryGame? {
        val id = readableDatabase.rawQuery("SELECT name, game_id FROM files WHERE game_id IS NOT NULL ORDER BY seen_at DESC", null).use { c ->
            var found: Long? = null
            while (found == null && c.moveToNext()) if (nameOf(c.getString(0)) == saveName) found = c.getLong(1)
            found
        } ?: return null
        return libraryGames().firstOrNull { it.id == id }
    }

    fun details(gameId: Long): GameDetails? =
        readableDatabase.rawQuery(
            "SELECT g.title, g.md5, (SELECT f.name FROM files f WHERE f.game_id = g.id ORDER BY f.seen_at DESC LIMIT 1)," +
                " g.region, g.revision, g.rom_size, g.last_played_at, g.play_seconds, a.kind, a.cover" +
                " FROM games g LEFT JOIN box_art a ON a.game_id = g.id WHERE g.id = ?",
            arrayOf(gameId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) null
            else GameDetails(
                title = c.getString(0), md5 = c.getString(1), file = c.getString(2),
                regionCode = if (c.isNull(3)) null else c.getInt(3).toChar(), revision = if (c.isNull(4)) null else c.getInt(4), romSize = if (c.isNull(5)) null else c.getLong(5),
                lastPlayedAt = if (c.isNull(6)) null else c.getLong(6), playSeconds = c.getLong(7),
                art = ArtKind.of(c.getString(8)), cover = c.getString(9),
            )
        }

    /** The user's Change box art choice: [kind] Custom, Hidden, or Libretro again; the libretro cover's name stays. */
    fun chooseArt(gameId: Long, kind: ArtKind) {
        writableDatabase.transaction {
            val row = ContentValues().apply { put("kind", kind.db) }
            if (update("box_art", row, "game_id = ?", arrayOf(gameId.toString())) == 0) {
                insertOrThrow("box_art", null, row.apply { put("game_id", gameId); put("checked_at", 0L) })
            }
        }
    }

    /**
     * Games to look up against the cover listing fetched at [listedAt]: never looked up, or
     * "none" against an older listing. Library games only.
     */
    fun artToLookUp(listedAt: Long): List<ArtLookup> =
        readableDatabase.rawQuery(
            "SELECT g.id, g.md5, (SELECT f.name FROM files f WHERE f.game_id = g.id ORDER BY f.seen_at DESC LIMIT 1) n" +
                " FROM games g LEFT JOIN box_art a ON a.game_id = g.id" +
                " WHERE n IS NOT NULL AND (a.kind IS NULL OR (a.kind = 'none' AND a.checked_at < ?)) ORDER BY g.title COLLATE NOCASE",
            arrayOf(listedAt.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(ArtLookup(c.getLong(0), c.getString(1), c.getString(2).substringBeforeLast('.'))) } }

    /** Records what [gameId] shows; see [LibrarySchema.V2]. */
    fun setArt(gameId: Long, kind: String, cover: String?, checkedAt: Long) {
        writableDatabase.insertWithOnConflict("box_art", null, ContentValues().apply {
            put("game_id", gameId)
            put("kind", kind)
            put("cover", cover)
            put("checked_at", checkedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** A game's files, most recently seen first (the same ROM can sit in two folders). */
    fun filesOf(gameId: Long): List<GameFile> =
        readableDatabase.rawQuery("SELECT uri, tree_uri, name FROM files WHERE game_id = ? ORDER BY seen_at DESC", arrayOf(gameId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(GameFile(c.getString(0), c.getString(1), c.getString(2))) }
        }

    /** Games with at least one file in a ROM folder: the library. */
    fun gameCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(DISTINCT game_id) FROM files WHERE game_id IS NOT NULL", null).use { c -> c.moveToFirst(); c.getInt(0) }

    /**
     * The progress cache: the set of the game with RetroAchievements hash [md5], as rc_client gave it
     * ([game] its summary, [list] its achievements). False when the game isn't in the library.
     */
    fun saveAchievements(md5: String, game: org.json.JSONObject, list: String, now: Long = System.currentTimeMillis()): Boolean {
        val db = writableDatabase
        val id = db.rawQuery("SELECT id FROM games WHERE md5 = ?", arrayOf(md5.lowercase())).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            ?: return false
        db.insertWithOnConflict("ra_sets", null, ContentValues().apply {
            put("game_id", id); put("ra_game_id", game.optInt("id")); put("title", game.optString("title"))
            put("achievements", game.optInt("achievements")); put("points", game.optInt("points"))
            put("earned", game.optInt("unlocked")); put("earned_hardcore", 0); put("fetched_at", now)
            put("badge", game.optString("badge")); put("game_json", game.toString()); put("list_json", list)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return true
    }

    /** Every cached set: (game id, its summary JSON, the game's last-played ms). */
    fun achievementGames(): List<Triple<Long, String, Long>> =
        readableDatabase.rawQuery(
            "SELECT s.game_id, s.game_json, COALESCE(g.last_played_at, 0) FROM ra_sets s JOIN games g ON g.id = s.game_id WHERE s.game_json IS NOT NULL", null,
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getLong(2))) } }

    /** One game's cached set: (summary JSON, achievement list JSON, when it was kept), or null. */
    fun achievementsOf(gameId: Long): Triple<String, String, Long>? =
        readableDatabase.rawQuery("SELECT game_json, list_json, fetched_at FROM ra_sets WHERE game_id = ? AND game_json IS NOT NULL", arrayOf("$gameId"))
            .use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1) ?: "[]", c.getLong(2)) else null }

    /** Sign-out: the cached progress belonged to that account. */
    fun clearAchievementCache() {
        writableDatabase.transaction {
            for (t in LibrarySchema.RA_CACHE) delete(t, null, null)
        }
    }

    companion object {
        const val NAME = "library.db"
        /** The built-in collection's name, which no other can take. */
        const val FAVOURITES = "Favourites"

        @Volatile private var instance: LibraryDb? = null

        fun get(context: Context): LibraryDb =
            instance ?: synchronized(this) { instance ?: LibraryDb(context.applicationContext).also { instance = it } }
    }
}
