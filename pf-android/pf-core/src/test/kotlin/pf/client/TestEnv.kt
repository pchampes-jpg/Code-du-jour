package pf.client

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Lance le serveur Python v0.1.2 NON MODIFIÉ (HTTPS local, certificats de test jetables, données fictives). */
class TestServer(sanDns: String? = null) : AutoCloseable {
    val work: File = Files.createTempDirectory("pf-essai").toFile()
    private val process: Process
    private val out: BufferedReader
    private val inp: BufferedWriter
    val url: String
    val caPem: ByteArray

    init {
        val plat = System.getenv("PF_PLATFORM_DIR").orEmpty()
        check(plat.isNotEmpty() && File(plat, "server/src/pf_server").isDirectory) {
            "PF_PLATFORM_DIR doit pointer vers l'arbre de la plateforme v0.1.2 (lecture seule)"
        }
        val script = File(System.getProperty("user.dir")).parentFile.resolve("tools/serveur_essai.py")
        val cmd = mutableListOf("python3", "-I", "-B", script.absolutePath, plat, File(work, "srv").absolutePath)
        if (sanDns != null) cmd += listOf("--san-dns", sanDns)
        process = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        out = process.inputStream.bufferedReader()
        inp = process.outputStream.bufferedWriter()
        val hello = JSONObject(out.readLine() ?: error("le serveur d'essai n'a pas démarré"))
        url = hello.getString("url")
        caPem = File(hello.getString("ca")).readBytes()
    }

    @Synchronized
    fun cmd(json: JSONObject): JSONObject {
        inp.write(json.toString()); inp.newLine(); inp.flush()
        return JSONObject(out.readLine())
    }

    fun enrollmentCode(perms: String = "*", apps: List<String> = listOf("essai"), ttl: Int = 3600): String =
        cmd(JSONObject().put("cmd", "enroll").put("perms", perms).put("apps", org.json.JSONArray(apps)).put("ttl", ttl)).getString("code")

    fun revoke(deviceId: String) = cmd(JSONObject().put("cmd", "revoke").put("device_id", deviceId))
    fun opsCount(app: String = "essai"): Int = cmd(JSONObject().put("cmd", "count_ops").put("app_id", app)).getInt("n")
    fun serverEntity(entity: String, id: String, app: String = "essai"): JSONObject? =
        cmd(JSONObject().put("cmd", "entity").put("app_id", app).put("entity", entity).put("entity_id", id)).let { if (it.isNull("row")) null else it.getJSONObject("row") }
    fun serverFile(fileId: String, app: String = "essai"): JSONObject = cmd(JSONObject().put("cmd", "file").put("app_id", app).put("file_id", fileId))

    override fun close() {
        try { cmd(JSONObject().put("cmd", "stop")) } catch (_: Exception) { }
        process.waitFor(10, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
        work.deleteRecursively()
    }
}

/** Un « appareil » de test : base SQLite fichier, dossier de fichiers, coffre logiciel. Peut être fermé/rouvert (redémarrage). */
class TestDevice(
    val server: TestServer,
    val appId: String = "essai",
    var transport: Transport = HttpsTransport(server.url, server.caPem),
    val chunkSize: Int = 65_536,
    var nowFn: () -> Long = System::currentTimeMillis,
    val vault: SecretVault = SoftwareVault(),
) {
    val dir: File = Files.createTempDirectory("pf-dev").toFile()
    val dbFile = File(dir, "pf.db")
    lateinit var client: PlatformClient

    init { open() }

    fun open(): PlatformClient {
        client = PlatformClient(
            ClientConfig(appId, File(dir, "files"), chunkSize = chunkSize, backoffBaseSeconds = 0.0, now = { nowFn() }),
            JdbcDb(dbFile.absolutePath), transport, vault)
        return client
    }

    /** Simule l'arrêt de l'application puis son redémarrage : mêmes fichiers, même coffre. */
    fun restart(): PlatformClient { client.close(); return open() }

    fun enroll(perms: String = "*") = client.enroll(server.enrollmentCode(perms, listOf(appId)))

    fun cleanup() { try { client.close() } catch (_: Exception) { }; dir.deleteRecursively() }
}

/** Transport décoré pour injecter des pannes réseau précises. */
class FaultTransport(private val inner: Transport) : Transport {
    @Volatile var offline = false
    /** Après N requêtes correspondant au filtre, coupe le réseau (la requête N+1 échoue avant d'atteindre le serveur). */
    @Volatile var failAfter: Int = -1
    @Volatile var failFilter: (String, String) -> Boolean = { _, _ -> false }
    /** Si vrai : la prochaine requête atteint le serveur mais la réponse est « perdue ». */
    @Volatile var dropNextResponseFor: ((String, String) -> Boolean)? = null
    @Volatile var forge401Once = false
    val log = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var counter = 0

    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        log.add("$method ${path.substringBefore('?')}")
        if (offline) throw TransportException("ConnectException")
        if (failAfter >= 0 && failFilter(method, path)) {
            synchronized(this) { if (counter++ >= failAfter) throw TransportException("SocketException") }
        }
        if (forge401Once && headers.containsKey("Authorization")) {
            forge401Once = false
            return HttpResponse(401, emptyMap(), """{"code":"AUTH_EXPIRED","title":"Jeton expiré","status":401,"retryable":false}""".toByteArray())
        }
        val resp = inner.request(method, path, headers, body)
        val drop = dropNextResponseFor
        if (drop != null && drop(method, path)) {
            dropNextResponseFor = null
            throw TransportException("SocketTimeoutException")
        }
        return resp
    }

    fun count(prefix: String) = synchronized(log) { log.count { it.startsWith(prefix) } }
}
