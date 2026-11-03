package pf.android

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import pf.client.Row
import pf.client.SqlDb
import java.io.File

/**
 * SqlDb sur SQLiteDatabase (WAL, synchronous=FULL : une opération acceptée survit à une coupure de courant).
 * transaction() = beginTransactionNonExclusive() : BEGIN IMMEDIATE en mode WAL, imbrication gérée par SQLiteDatabase.
 */
class AndroidSqlDb(file: File) : SqlDb {
    private val db: SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).also {
        it.enableWriteAheadLogging()
        it.execSQL("PRAGMA synchronous=FULL")
    }

    override fun execute(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map(::bindable).toTypedArray())
    }

    private fun bindable(a: Any?): Any? = when (a) {
        null, is String, is Long, is Double, is ByteArray -> a
        is Int -> a.toLong()
        is Boolean -> if (a) 1L else 0L
        else -> a.toString()
    }

    override fun query(sql: String, vararg args: Any?): List<Row> {
        // rawQuery ne lie que des chaînes ; l'affinité de colonne SQLite assure la conversion pour les comparaisons.
        db.rawQuery(sql, args.map { it?.toString() ?: "" }.toTypedArray()).use { c ->
            val out = ArrayList<Row>(c.count)
            val names = c.columnNames
            while (c.moveToNext()) {
                val m = LinkedHashMap<String, Any?>(names.size * 2)
                for (i in names.indices) m[names[i]] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    else -> c.getString(i)
                }
                out.add(m)
            }
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransactionNonExclusive()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    override fun userVersion(): Int = db.version
    override fun setUserVersion(version: Int) { db.execSQL("PRAGMA user_version = $version") }
    override fun close() { db.close() }
}
