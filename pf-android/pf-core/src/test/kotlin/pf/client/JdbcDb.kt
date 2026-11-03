package pf.client

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** SqlDb JDBC pour les tests JVM (sqlite-jdbc). Même sémantique transactionnelle que l'implémentation Android. */
class JdbcDb(path: String) : SqlDb {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.autoCommit = true
        it.createStatement().use { s -> s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL"); s.execute("PRAGMA busy_timeout=30000") }
    }
    private val lock = ReentrantLock()
    private var depth = 0

    private fun bind(ps: java.sql.PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> ps.setObject(i + 1, null)
                is Int -> ps.setLong(i + 1, a.toLong())
                is Long -> ps.setLong(i + 1, a)
                is Boolean -> ps.setLong(i + 1, if (a) 1 else 0)
                is Double -> ps.setDouble(i + 1, a)
                is ByteArray -> ps.setBytes(i + 1, a)
                else -> ps.setString(i + 1, a.toString())
            }
        }
    }

    override fun execute(sql: String, vararg args: Any?) = lock.withLock {
        conn.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }
        Unit
    }

    override fun query(sql: String, vararg args: Any?): List<Row> = lock.withLock {
        conn.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val md = rs.metaData
                val out = ArrayList<Row>()
                while (rs.next()) {
                    val m = LinkedHashMap<String, Any?>()
                    for (c in 1..md.columnCount) m[md.getColumnLabel(c)] = rs.getObject(c)
                    out.add(m)
                }
                out
            }
        }
    }

    override fun <T> transaction(block: () -> T): T = lock.withLock {
        if (depth > 0) return@withLock block()
        conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        depth = 1
        try {
            val r = block()
            conn.createStatement().use { it.execute("COMMIT") }
            r
        } catch (t: Throwable) {
            try { conn.createStatement().use { it.execute("ROLLBACK") } } catch (_: Exception) { }
            throw t
        } finally { depth = 0 }
    }

    override fun userVersion(): Int = query("PRAGMA user_version").first().values.first().let { (it as Number).toInt() }
    override fun setUserVersion(version: Int) { execute("PRAGMA user_version = $version") }
    override fun close() { conn.close() }
}
