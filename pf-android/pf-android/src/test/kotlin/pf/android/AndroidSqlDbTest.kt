package pf.android

import org.robolectric.RuntimeEnvironment
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pf.client.*
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSqlDbTest {
    private fun file(): File = File(RuntimeEnvironment.getApplication().filesDir, "t-" + System.nanoTime() + ".db")

    @Test fun typesTransactionsEtVersion() {
        val f = file(); val db = AndroidSqlDb(f)
        db.execute("CREATE TABLE t(a INTEGER, b TEXT, c REAL, d BLOB)")
        db.execute("INSERT INTO t VALUES(?,?,?,?)", 7L, "é", 1.5, byteArrayOf(1, 2))
        db.execute("INSERT INTO t VALUES(?,?,?,?)", 8, null, 2.5, null)
        val rows = db.query("SELECT * FROM t ORDER BY a")
        assertEquals(7L, rows[0].long("a")); assertEquals("é", rows[0].str("b")); assertEquals(1.5, rows[0].double("c"))
        assertContentEquals(byteArrayOf(1, 2), rows[0]["d"] as ByteArray); assertNull(rows[1]["b"])
        assertEquals(1, db.query("SELECT * FROM t WHERE a=?", "7").size)

        assertFailsWith<IllegalStateException> { db.transaction { db.execute("INSERT INTO t(a) VALUES(99)"); error("annule") } }
        assertTrue(db.query("SELECT * FROM t WHERE a=99").isEmpty(), "rollback sur exception")
        db.transaction { db.transaction { db.execute("INSERT INTO t(a) VALUES(100)") }; db.execute("INSERT INTO t(a) VALUES(101)") }
        assertEquals(2, db.query("SELECT * FROM t WHERE a>=100").size, "imbrication")
        db.setUserVersion(3); assertEquals(3, db.userVersion())
        db.close()
        val again = AndroidSqlDb(f) // persistance après fermeture
        assertEquals(3, again.userVersion()); assertEquals(4, again.query("SELECT * FROM t").size)
        again.close()
    }

    @Test fun lesMigrationsDuMagasinS_appliquentSurSQLiteAndroid() {
        val db = AndroidSqlDb(file())
        val tmp = File(RuntimeEnvironment.getApplication().filesDir, "files")
        val client = PlatformClient(ClientConfig("essai", tmp), db, object : Transport {
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse =
                throw TransportException("ConnectException")
        }, SoftwareVault())
        val id = client.addFile(ByteArray(10) { 3 }, "image/png")
        client.create("note", "n-1", org.json.JSONObject().put("t", "hors ligne"), files = listOf(id))
        assertEquals(1, client.status().pendingOps)
        assertEquals("hors ligne", client.get("note", "n-1")!!.payload.getString("t"))
        assertEquals(PlatformClient.SCHEMA_VERSION, db.userVersion())
        client.close()
    }
}
