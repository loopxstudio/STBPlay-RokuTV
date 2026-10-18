package com.stbplay.app.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stbplay.app.data.SecureValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Protect legacy values once; ordinary opens never walk/decrypt the whole catalogue. */
internal object ProtectedDatabaseMigration : RoomDatabase.Callback() {
    private val status = MutableStateFlow("Preparing saved sources…")
    val progress = status.asStateFlow()
    private const val PENDING_COMPACTION = 1
    private const val COMPLETE = 2

    override fun onOpen(db: SupportSQLiteDatabase) {
        db.query("PRAGMA busy_timeout=5000").use { it.moveToFirst() }
        db.query("PRAGMA secure_delete=ON").use { it.moveToFirst() }
        db.execSQL("CREATE TABLE IF NOT EXISTS stbplay_storage_state (id INTEGER PRIMARY KEY, protectionVersion INTEGER NOT NULL)")
        val version = db.query("SELECT protectionVersion FROM stbplay_storage_state WHERE id=1").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        if (version == COMPLETE) return
        var compact = version == PENDING_COMPACTION
        if (!compact) {
            var changed = false
            db.beginTransaction()
            try {
                fun protect(table: String, columns: List<String>, label: String) {
                    val legacy = columns.joinToString(" OR ") { "($it IS NOT NULL AND $it<>'' AND substr($it,1,11)<>'stb.enc.v1:')" }
                    status.value = label
                    db.compileStatement("UPDATE $table SET ${columns.joinToString(",") { "$it=?" }} WHERE rowid=?").use { update ->
                        var lastRow = Long.MIN_VALUE
                        var processed = 0
                        while (true) {
                            // Close each bounded cursor before updating its rows. A large
                            // Android CursorWindow can re-run SELECT when refilled;
                            // updating its filter in place would then skip legacy rows.
                            val batch = mutableListOf<Pair<Long, List<String?>>>()
                            db.query("SELECT rowid,${columns.joinToString(",")} FROM $table WHERE rowid>? AND ($legacy) ORDER BY rowid LIMIT 256", arrayOf(lastRow)).use { rows ->
                                while (rows.moveToNext()) batch.add(rows.getLong(0) to columns.indices.map { if (rows.isNull(it+1)) null else rows.getString(it+1) })
                            }
                            if (batch.isEmpty()) break
                            for ((rowId, values) in batch) {
                                update.clearBindings()
                                values.forEachIndexed { i, value ->
                                    if (value == null) update.bindNull(i+1)
                                    else update.bindString(i+1, if (SecureValues.encrypted(value)) value else SecureValues.encrypt(value))
                                }
                                update.bindLong(columns.size+1, rowId)
                                update.executeUpdateDelete()
                                lastRow = rowId
                                processed++
                                changed = true
                            }
                            status.value = "$label $processed items"
                        }
                    }
                }
                protect("playlists", listOf("url", "usernameOrMac", "password", "epgUrl"), "Preparing saved profiles…")
                protect("media_items", listOf("streamUrl", "cmd", "logo"), "Preparing saved catalogue…")
                protect("favorite_items", listOf("payload"), "Preparing favorites…")
                compact = changed
                db.execSQL("INSERT OR REPLACE INTO stbplay_storage_state VALUES (1,?)", arrayOf(if (changed) PENDING_COMPACTION else COMPLETE))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        if (compact) {
            status.value = "Finishing saved source preparation…"
            // Persist the pending phase before compaction. An interrupted upgrade can
            // finish cleanup on next open without re-encrypting or losing profiles.
            db.execSQL("VACUUM")
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            db.execSQL("UPDATE stbplay_storage_state SET protectionVersion=? WHERE id=1", arrayOf(COMPLETE))
        }
    }
}
