package pf.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ClientConfig(
    val appId: String,
    /** Dossier privé de l'application où sont copiés photos et documents avant envoi. */
    val filesDir: File,
    val chunkSize: Int = 256 * 1024,      // serveur : 64 Kio à 8 Mio
    val batchSize: Int = 50,              // serveur : 1 à 100 opérations par lot
    val backoffBaseSeconds: Double = 2.0,
    val clientVersion: String = "pf-android-lib/0.1.0",
    val now: () -> Long = System::currentTimeMillis,
) {
    init {
        require(Regex("^[a-z][a-z0-9_-]{1,31}$").matches(appId)) { "app_id invalide" }
        require(chunkSize in 65_536..8 * 1024 * 1024) { "chunkSize hors limites du serveur" }
        require(batchSize in 1..100) { "batchSize hors limites du serveur" }
    }
}

/**
 * Client de la Plateforme Commune (contrat v0.1.2) : enregistrement local hors ligne, synchronisation idempotente,
 * fichiers avec reprise. Port fidèle du client de référence Python.
 *
 * Machine à états d'une opération locale :
 *   PENDING -> SENDING -> ACKED (APPLIED ou DUPLICATE)
 *                      -> REJECTED | CONFLICT   (conservées, jamais supprimées automatiquement)
 *                      -> WAITING_FILE          (fichier non complet côté serveur ; renvoyée plus tard)
 *   panne réseau : SENDING -> PENDING (renvoi sûr grâce à l'idempotence (app_id, op_id) côté serveur)
 *
 * Sûr entre threads : les écritures locales sont transactionnelles ; une seule synchronisation à la fois.
 */
class PlatformClient(
    private val config: ClientConfig,
    db: SqlDb,
    private val transport: Transport,
    private val vault: SecretVault,
) : AutoCloseable {
    private val store = LocalStore(db)
    private val sql get() = store.db
    private val syncLock = ReentrantLock()
    private val listeners = CopyOnWriteArrayList<SyncStatusListener>()
    private val random = SecureRandom()
    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpMillis: Long = 0
    @Volatile private var clockOffsetMillis: Long = 0
    @Volatile private var syncing = false
    @Volatile private var lastNotify = 0L

    /** Appelé après chaque enregistrement local : le coordinateur y branche la synchronisation automatique. */
    @Volatile var onLocalChange: (() -> Unit)? = null

    init {
        val bound = store.getMeta("app_id")
        if (bound == null) store.setMeta("app_id", config.appId)
        else if (bound != config.appId) throw PfUsageException("Cette base locale appartient à l'application '$bound'")
        config.filesDir.mkdirs()
        sql.execute("UPDATE outbox SET state='PENDING' WHERE state='SENDING'") // reprise après arrêt brutal
    }

    override fun close() { sql.close() }

    // ----------------------------------------------------------------- identité
    val deviceId: String? get() = store.getMeta("device_id")
    val isEnrolled: Boolean get() = deviceId != null && store.getMeta("device_key") != null

    /**
     * Enrôle l'appareil avec un code à usage unique (secret : ne jamais le journaliser ni l'afficher).
     * La clé privée est créée ici, enveloppée par le coffre, et ne quitte jamais l'appareil.
     * Si la réponse du serveur est perdue après consommation du code, l'administrateur doit en émettre un nouveau.
     */
    fun enroll(enrollmentCode: String) {
        val key = DeviceKey.generate()
        val body = JSONObject()
            .put("enrollment_code", enrollmentCode)
            .put("device_public_key", Util.b64(key.publicKey))
            .put("app_ids", JSONArray().put(config.appId))
            .put("client_version", config.clientVersion)
        val resp = request("POST", "/v1/devices/enroll", auth = false, body = body)
        val deviceId = resp.json().getString("device_id")
        val wrapped = vault.wrap(key.seedBytes())
        sql.transaction {
            store.setMeta("device_id", deviceId)
            store.setMeta("device_key", Util.b64(wrapped))
            store.setMeta("auth_state", "ok")
        }
        accessToken = null
        notifyStatus(true)
    }

    private fun authenticate() {
        val deviceId = store.getMeta("device_id")
        val wrapped = store.getMeta("device_key")
        if (deviceId == null || wrapped == null) throw RemoteException(401, "AUTH_REQUIRED", "Appareil non enrôlé")
        val localNow = config.now()
        val ts = Util.iso(localNow + clockOffsetMillis)
        val nonceBytes = ByteArray(12).also { random.nextBytes(it) }
        val nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val seed = vault.unwrap(Util.b64decode(wrapped))
        val sig = try { DeviceKey.fromSeed(seed).sign("pf-token|$deviceId|$ts|$nonce".toByteArray(Charsets.UTF_8)) } finally { seed.fill(0) }
        val data = request("POST", "/v1/auth/token", auth = false, body = JSONObject()
            .put("device_id", deviceId).put("timestamp", ts).put("nonce", nonce).put("signature", Util.b64(sig))).json()
        accessToken = data.getString("access_token")
        accessExpMillis = localNow + data.getLong("expires_in") * 1000
        sql.transaction {
            store.setMeta("permissions", data.getJSONArray("permissions").toString())
            store.setMeta("permissions_until", localNow + data.getLong("permissions_ttl") * 1000)
            store.setMeta("auth_state", "ok")
        }
    }

    private fun token(): String {
        val t = accessToken
        if (t != null && accessExpMillis - 30_000 > config.now()) return t
        authenticate()
        return accessToken!!
    }

    /**
     * Indication pour l'interface hors ligne : permissions reçues à la dernière connexion, pendant leur durée de
     * validité (12 h par défaut côté serveur). Ce n'est PAS une barrière de sécurité : le serveur applique les
     * permissions à chaque synchronisation.
     */
    fun canOffline(permission: String): Boolean {
        val until = store.getMeta("permissions_until")?.toLongOrNull() ?: 0
        if (until <= config.now()) return false
        val perms = JSONArray(store.getMeta("permissions") ?: "[]")
        val list = (0 until perms.length()).map { perms.getString(it) }
        return "*" in list || permission in list || "${permission.substringBefore(':')}:*" in list
    }

    // ----------------------------------------------------------------- requêtes
    private fun request(
        method: String, path: String, body: JSONObject? = null, raw: ByteArray? = null,
        headers: Map<String, String> = emptyMap(), auth: Boolean = true,
    ): HttpResponse {
        var resp: HttpResponse? = null
        for (attempt in 0..1) {
            val h = HashMap(headers)
            var data = raw
            if (body != null) {
                data = body.toString().toByteArray(Charsets.UTF_8)
                h["Content-Type"] = "application/json"
            }
            if (auth) h["Authorization"] = "Bearer " + token()
            resp = transport.request(method, path, h, data)
            if (resp.status == 401 && auth && attempt == 0) { accessToken = null; continue } // jeton expiré : une seule nouvelle signature
            break
        }
        val r = resp!!
        if (r.status >= 400) {
            val p = r.jsonOrNull()
            val code = p?.optString("code", "INTERNAL_ERROR") ?: "INTERNAL_ERROR"
            if (code == "DEVICE_REVOKED") store.setMeta("auth_state", "revoked")
            val detail = p?.optString("detail", "")?.takeIf { it.isNotEmpty() } ?: p?.optString("title", "") ?: ""
            throw RemoteException(r.status, code, detail, (p?.optBoolean("retryable", false) ?: false) || r.status == 429 || r.status == 503)
        }
        return r
    }

    // ------------------------------------------------- enregistrement local
    /** Crée une entité. Retourne l'op_id. Fonctionne hors ligne ; l'envoi est automatique et idempotent. */
    fun create(entity: String, entityId: String, payload: JSONObject, numbering: Numbering? = null, files: List<String> = emptyList()): String =
        enqueue("create", entity, entityId, payload, numbering, files)

    /** Modification partielle : seuls les champs de [patch] sont changés. */
    fun update(entity: String, entityId: String, patch: JSONObject, files: List<String> = emptyList()): String =
        enqueue("update", entity, entityId, patch, null, files)

    fun delete(entity: String, entityId: String): String = enqueue("delete", entity, entityId, JSONObject(), null, emptyList())

    private fun enqueue(verb: String, entity: String, entityId: String, payload: JSONObject, numbering: Numbering?, files: List<String>): String {
        if (!Util.ENTITY_RE.matches(entity)) throw PfUsageException("Nom d'entité invalide")
        if (!Util.ENTITY_ID_RE.matches(entityId)) throw PfUsageException("Identifiant d'entité invalide")
        val payloadJson = payload.toString()
        if (payloadJson.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) throw PfUsageException("Charge utile trop volumineuse : utiliser un fichier")
        val opId = UUID.randomUUID().toString()
        sql.transaction {
            val view = viewOf(entity, entityId)
            if (verb == "create" && view != null) throw PfUsageException("L'entité existe déjà localement")
            if (verb != "create" && view == null) throw PfUsageException("Entité inconnue localement")
            val conf = sql.queryOne("SELECT version FROM entities WHERE entity=? AND entity_id=?", entity, entityId)
            val pending = sql.queryOne(
                "SELECT COUNT(*) AS n FROM outbox WHERE entity=? AND entity_id=? AND state IN ('PENDING','SENDING','WAITING_FILE')",
                entity, entityId)!!.long("n")
            val base = (conf?.long("version") ?: 0L) + pending
            for (fid in files) {
                if (sql.queryOne("SELECT 1 AS x FROM files WHERE file_id=?", fid) == null)
                    throw PfUsageException("Fichier inconnu : l'ajouter d'abord avec addFile()")
            }
            val numberingJson = numbering?.let {
                JSONObject().put("series", it.series).also { j -> if (it.year != null) j.put("year", it.year) }.toString()
            }
            sql.execute(
                "INSERT INTO outbox(op_id, state, type, entity, entity_id, base_version, schema_version, created_at_device," +
                    " payload_json, numbering_json, files_json) VALUES(?, 'PENDING', ?,?,?,?,1,?,?,?,?)",
                opId, "$entity.$verb", entity, entityId, base, Util.iso(config.now()), payloadJson, numberingJson,
                if (files.isEmpty()) null else JSONArray(files).toString())
        }
        notifyStatus(true)
        try { onLocalChange?.invoke() } catch (_: Exception) { }
        return opId
    }

    private fun viewOf(entity: String, entityId: String): EntityView? {
        val conf = sql.queryOne("SELECT * FROM entities WHERE entity=? AND entity_id=?", entity, entityId)
        var payload: JSONObject? = conf?.let { JSONObject(it.str("payload_json")) }
        var deleted = conf?.long("deleted") == 1L
        val number = conf?.str("official_number")
        val version = conf?.long("version") ?: 0L
        val ops = sql.query(
            "SELECT type, payload_json FROM outbox WHERE entity=? AND entity_id=? AND state IN ('PENDING','SENDING','WAITING_FILE') ORDER BY seq",
            entity, entityId)
        var exists = conf != null
        for (op in ops) {
            when (op.str("type")!!.substringAfterLast('.')) {
                "create" -> { payload = JSONObject(op.str("payload_json")); deleted = false; exists = true }
                "update" -> if (payload != null) payload = merge(payload, JSONObject(op.str("payload_json")))
                "delete" -> deleted = true
            }
        }
        if (!exists || deleted || payload == null) return null
        return EntityView(entity, entityId, payload, version, number, ops.size)
    }

    private fun merge(base: JSONObject, patch: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        for (k in patch.keys()) out.put(k, patch.get(k))
        return out
    }

    /** Vue locale : données confirmées par le serveur + modifications en attente d'envoi. */
    fun get(entity: String, entityId: String): EntityView? = viewOf(entity, entityId)

    /** Toutes les entités visibles d'un type (confirmées ou créées localement, non supprimées). */
    fun list(entity: String): List<EntityView> {
        val ids = LinkedHashSet<String>()
        sql.query("SELECT entity_id FROM entities WHERE entity=? ORDER BY entity_id", entity).forEach { ids.add(it.str("entity_id")!!) }
        sql.query("SELECT DISTINCT entity_id FROM outbox WHERE entity=? AND state IN ('PENDING','SENDING','WAITING_FILE')", entity)
            .forEach { ids.add(it.str("entity_id")!!) }
        return ids.sorted().mapNotNull { viewOf(entity, it) }
    }

    // ------------------------------------------------------------------ fichiers
    /** Copie un fichier (ex. photo prise par l'appareil) dans le dossier privé, en flux, avec son empreinte SHA-256. */
    fun addFile(source: File, mime: String): String = source.inputStream().use { addFile(it, mime) }

    fun addFile(data: ByteArray, mime: String): String = addFile(data.inputStream(), mime)

    fun addFile(input: InputStream, mime: String): String {
        if (!Util.MIME_RE.matches(mime)) throw PfUsageException("Type MIME invalide")
        val fileId = UUID.randomUUID().toString()
        val tmp = File(config.filesDir, "$fileId.tmp")
        val dest = File(config.filesDir, fileId)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            java.io.FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); out.write(buf, 0, n); size += n
                }
                out.flush(); out.fd.sync() // durable avant d'être référencé
            }
            if (size < 1) throw PfUsageException("Fichier vide")
            if (size > MAX_FILE_BYTES) throw PfUsageException("Fichier trop volumineux (max 512 Mio)")
            if (!tmp.renameTo(dest)) throw PfUsageException("Impossible d'enregistrer le fichier")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        try {
            sql.execute("INSERT INTO files(file_id, path, size, sha256, mime, state, chunk_size) VALUES(?,?,?,?,?, 'LOCAL', ?)",
                fileId, dest.absolutePath, size, Util.hex(md.digest()), mime, config.chunkSize)
        } catch (e: Exception) { dest.delete(); throw e }
        notifyStatus(true)
        try { onLocalChange?.invoke() } catch (_: Exception) { }
        return fileId
    }

    /** Fichier local d'un file_id (pour afficher une photo). */
    fun localFile(fileId: String): File? = sql.queryOne("SELECT path FROM files WHERE file_id=?", fileId)?.str("path")?.let(::File)?.takeIf { it.exists() }

    /** Relance un fichier en échec (ex. après restauration du fichier local). */
    fun retryFile(fileId: String) {
        sql.execute("UPDATE files SET state='LOCAL', upload_id=NULL, sent_bytes=0, last_error=NULL WHERE file_id=? AND state='FAILED'", fileId)
        notifyStatus(true)
        try { onLocalChange?.invoke() } catch (_: Exception) { }
    }

    /**
     * Supprime les copies locales des fichiers entièrement transférés dont toutes les opérations sont acquittées,
     * pour libérer le stockage de la tablette. Jamais appelée automatiquement. Retourne le nombre supprimé.
     */
    fun purgeUploadedFiles(): Int {
        var n = 0
        for (f in sql.query("SELECT file_id, path FROM files WHERE state='COMPLETE'")) {
            val fid = f.str("file_id")!!
            val used = sql.query(
                "SELECT 1 AS x FROM outbox WHERE state NOT IN ('ACKED') AND files_json LIKE ?", "%\"$fid\"%").isNotEmpty()
            if (used) continue
            File(f.str("path")!!).delete()
            sql.execute("UPDATE files SET state='PURGED' WHERE file_id=?", fid)
            n++
        }
        return n
    }

    private fun uploadFiles(rep: SyncReport) {
        for (f in sql.query("SELECT * FROM files WHERE state IN ('LOCAL','UPLOADING') ORDER BY rowid")) uploadOne(f, rep)
    }

    private fun failFile(fileId: String, error: String) {
        sql.execute("UPDATE files SET state='FAILED', last_error=? WHERE file_id=?", error, fileId)
    }

    private fun bytesOf(index: Long, size: Long, chunk: Long): Long = minOf(chunk, size - index * chunk)

    private fun uploadOne(f: Row, rep: SyncReport) {
        val fileId = f.str("file_id")!!
        val path = File(f.str("path")!!)
        val size = f.long("size")
        if (!path.exists()) { failFile(fileId, "LOCAL_FILE_MISSING"); return }
        if (path.length() != size || Util.sha256Hex(path) != f.str("sha256")) { failFile(fileId, "LOCAL_FILE_ALTERED"); return } // jamais transmis
        val chunkSize = f.long("chunk_size").takeIf { it > 0 } ?: config.chunkSize.toLong()
        try {
            val init = request("POST", "/v1/files", body = JSONObject()
                .put("file_id", fileId).put("app_id", config.appId).put("size", size)
                .put("sha256", f.str("sha256")).put("mime", f.str("mime")).put("chunk_size", chunkSize)).json()
            val uploadId = init.getString("upload_id")
            sql.execute("UPDATE files SET upload_id=?, state='UPLOADING' WHERE file_id=?", uploadId, fileId)
            if (init.getString("state") != "complete") {
                val status = request("GET", "/v1/files/$uploadId").json()
                val have = HashSet<Long>().also { s -> val a = status.getJSONArray("received_indexes"); for (i in 0 until a.length()) s.add(a.getLong(i)) }
                val serverChunk = status.getLong("chunk_size")
                val count = (size + serverChunk - 1) / serverChunk
                var sent = have.sumOf { bytesOf(it, size, serverChunk) }
                sql.execute("UPDATE files SET sent_bytes=? WHERE file_id=?", sent, fileId)
                RandomAccessFile(path, "r").use { raf ->
                    for (idx in 0 until count) {
                        if (idx in have) continue
                        raf.seek(idx * serverChunk)
                        val buf = ByteArray(bytesOf(idx, size, serverChunk).toInt())
                        raf.readFully(buf)
                        request("PUT", "/v1/files/$uploadId/chunks/$idx", raw = buf, headers = mapOf(
                            "Content-Type" to "application/octet-stream", "X-Chunk-SHA256" to Util.sha256Hex(buf)))
                        rep.chunksSent++
                        sent += buf.size
                        sql.execute("UPDATE files SET sent_bytes=? WHERE file_id=?", sent, fileId)
                        notifyStatus(false)
                    }
                }
                try {
                    request("POST", "/v1/files/$uploadId/complete")
                } catch (e: RemoteException) {
                    if (e.code == "FILE_HASH_MISMATCH") // le serveur a réinitialisé le transfert : on repart à zéro au prochain passage
                        sql.execute("UPDATE files SET upload_id=NULL, state='LOCAL', sent_bytes=0 WHERE file_id=?", fileId)
                    throw e
                }
            }
            sql.execute("UPDATE files SET state='COMPLETE', sent_bytes=size, last_error=NULL WHERE file_id=?", fileId)
            rep.filesUploaded++
            notifyStatus(true)
        } catch (e: RemoteException) {
            // Refus définitif propre à ce fichier (ex. 422 taille/MIME, 409 conflit) : on l'isole, les autres continuent.
            if (e.status in 400..499 && e.status != 401 && e.status != 429 && e.code != "DEVICE_REVOKED" && e.code != "FILE_HASH_MISMATCH" &&
                e.code != "AUTH_REQUIRED" && e.code != "AUTH_EXPIRED" && e.code != "FORBIDDEN") {
                failFile(fileId, e.code); notifyStatus(true)
            } else throw e
        }
    }

    // ------------------------------------------------------------ synchronisation
    /**
     * Passe complète : fichiers, envoi des opérations, récupération des changements. Idempotente et réentrante
     * (une seule à la fois). [ignoreBackoff] : ignore les délais de réessai (ex. retour du réseau).
     */
    fun sync(ignoreBackoff: Boolean = false): SyncReport = syncLock.withLock {
        val rep = SyncReport()
        if (!isEnrolled) { rep.error = "NOT_ENROLLED"; rep.skipped = true; return rep }
        if (store.getMeta("auth_state") == "revoked") { rep.error = "DEVICE_REVOKED"; rep.skipped = true; return rep }
        sql.execute("UPDATE outbox SET state='PENDING' WHERE state='SENDING'")
        syncing = true
        notifyStatus(true)
        try {
            try {
                val health = request("GET", "/v1/health", auth = false).jsonOrNull()
                health?.optString("server_time", null)?.let {
                    try { clockOffsetMillis = java.time.Instant.parse(it).toEpochMilli() - config.now() } catch (_: Exception) { }
                }
            } catch (e: TransportException) {
                store.setMeta("reachable", "0")
                store.setMeta("last_error", if (e.isTlsRejection) "TLS" else "NETWORK")
                rep.error = if (e.isTlsRejection) "TLS" else "NETWORK"
                return rep
            } catch (e: RemoteException) {
                store.setMeta("reachable", "0")
                store.setMeta("last_error", e.code)
                rep.error = e.code
                return rep
            }
            rep.reachable = true
            store.setMeta("reachable", "1")
            try {
                uploadFiles(rep)
                push(rep, ignoreBackoff)
                pull(rep)
                store.setMeta("last_sync_at", config.now())
                store.setMeta("last_error", null)
            } catch (e: TransportException) {
                rep.error = if (e.isTlsRejection) "TLS" else "NETWORK"
            } catch (e: RemoteException) {
                rep.error = e.code
            } catch (e: java.security.GeneralSecurityException) {
                // La graine enveloppée ne peut plus être relue (coffre réinitialisé, restauration sur un autre appareil) :
                // l'appareil doit être ré-enrôlé ; les données locales restent conservées.
                rep.error = "DEVICE_KEY_UNUSABLE"
            }
            if (rep.error != null) store.setMeta("last_error", rep.error)
            return rep
        } finally {
            syncing = false
            notifyStatus(true)
        }
    }

    private fun dueOps(ignoreBackoff: Boolean): List<Row> {
        val now = config.now()
        val rows = sql.query("SELECT * FROM outbox WHERE state IN ('PENDING','WAITING_FILE') ORDER BY seq")
        val blocked = HashSet<Pair<String, String>>()
        val out = ArrayList<Row>()
        var bytes = 0
        for (r in rows) {
            val key = r.str("entity")!! to r.str("entity_id")!!
            val due = ignoreBackoff || r.long("next_attempt_at") <= now
            val files = r.str("files_json")?.let { JSONArray(it) }
            var ready = true
            if (files != null) for (i in 0 until files.length()) {
                if (sql.queryOne("SELECT state FROM files WHERE file_id=?", files.getString(i))?.str("state") != "COMPLETE") ready = false
            }
            // Jamais une opération avant celles qui la précèdent sur la même entité.
            if (key in blocked || !due || !ready) { blocked.add(key); continue }
            val size = r.str("payload_json")!!.length + 600
            if (out.isNotEmpty() && bytes + size > MAX_BATCH_BYTES) break
            bytes += size
            out.add(r)
            if (out.size >= config.batchSize) break
        }
        return out
    }

    private fun push(rep: SyncReport, ignoreBackoff: Boolean) {
        while (true) {
            val rows = dueOps(ignoreBackoff)
            if (rows.isEmpty()) return
            val ids = rows.map { it.str("op_id")!! }
            sql.execute("UPDATE outbox SET state='SENDING' WHERE op_id IN (${ids.joinToString(",") { "?" }})", *ids.toTypedArray())
            val ops = JSONArray()
            for (r in rows) {
                val op = JSONObject()
                    .put("op_id", r.str("op_id")).put("seq", r.long("seq")).put("type", r.str("type"))
                    .put("entity", r.str("entity")).put("entity_id", r.str("entity_id"))
                    .put("base_version", r.long("base_version")).put("schema_version", r.long("schema_version"))
                    .put("created_at_device", r.str("created_at_device")).put("payload", JSONObject(r.str("payload_json")))
                r.str("numbering_json")?.let { op.put("numbering", JSONObject(it)) }
                r.str("files_json")?.let { op.put("files", JSONArray(it)) }
                ops.put(op)
            }
            val resp = try {
                request("POST", "/v1/sync/push", body = JSONObject()
                    .put("app_id", config.appId).put("device_id", store.getMeta("device_id"))
                    .put("batch_id", UUID.randomUUID().toString()).put("ops", ops))
            } catch (e: TransportException) {
                release(ids, if (e.isTlsRejection) "TLS" else "NETWORK"); throw e
            } catch (e: RemoteException) {
                release(ids, e.code); throw e
            }
            if (applyResults(rows, resp.json().getJSONArray("results"), rep) == 0) return
        }
    }

    /** SENDING -> PENDING avec délai exponentiel (plafonné à 5 min) et gigue. */
    private fun release(ids: List<String>, error: String) {
        for (opId in ids) {
            val attempts = (sql.queryOne("SELECT attempts FROM outbox WHERE op_id=?", opId)?.long("attempts") ?: 0L) + 1
            val delaySec = if (config.backoffBaseSeconds > 0)
                minOf(300.0, Math.pow(config.backoffBaseSeconds, minOf(attempts, 8L).toDouble())) * (0.5 + random.nextDouble() / 2) else 0.0
            sql.execute("UPDATE outbox SET state='PENDING', attempts=?, next_attempt_at=?, last_error=? WHERE op_id=? AND state='SENDING'",
                attempts, config.now() + (delaySec * 1000).toLong(), error, opId)
        }
    }

    private fun applyResults(rows: List<Row>, results: JSONArray, rep: SyncReport): Int {
        val byId = rows.associateBy { it.str("op_id")!! }.toMutableMap()
        var progress = 0
        sql.transaction {
            for (i in 0 until results.length()) {
                val res = results.getJSONObject(i)
                val opId = res.getString("op_id")
                val row = byId.remove(opId) ?: continue
                when (val status = res.getString("status")) {
                    "APPLIED", "DUPLICATE" -> {
                        sql.execute("UPDATE outbox SET state='ACKED', result_json=?, last_error=NULL WHERE op_id=?", res.toString(), opId)
                        confirm(row, res)
                        rep.acked++; progress++
                    }
                    "PENDING_FILE" -> {
                        // Le serveur ne possède pas (ou plus) le fichier : on le retransmet au prochain passage.
                        row.str("files_json")?.let { fj ->
                            val a = JSONArray(fj)
                            for (k in 0 until a.length())
                                sql.execute("UPDATE files SET state='LOCAL', upload_id=NULL, sent_bytes=0 WHERE file_id=? AND state='COMPLETE'", a.getString(k))
                        }
                        release(listOf(opId), "FILE_INCOMPLETE") // SENDING -> PENDING avec délai de réessai
                        sql.execute("UPDATE outbox SET state='WAITING_FILE', result_json=? WHERE op_id=?", res.toString(), opId)
                        rep.waitingFile++
                    }
                    else -> {
                        val state = if (status == "CONFLICT") "CONFLICT" else "REJECTED"
                        sql.execute("UPDATE outbox SET state=?, result_json=?, last_error=? WHERE op_id=?",
                            state, res.toString(), res.optString("error_code", null), opId)
                        if (state == "CONFLICT") rep.conflicts++ else rep.rejected++
                        progress++
                    }
                }
            }
            for (row in byId.values) // opération absente de la réponse : renvoyée plus tard
                sql.execute("UPDATE outbox SET state='PENDING' WHERE op_id=? AND state='SENDING'", row.str("op_id"))
        }
        return progress
    }

    private fun confirm(row: Row, res: JSONObject) {
        val verb = row.str("type")!!.substringAfterLast('.')
        val entity = row.str("entity")!!
        val entityId = row.str("entity_id")!!
        val cur = sql.queryOne("SELECT * FROM entities WHERE entity=? AND entity_id=?", entity, entityId)
        val payload = JSONObject(row.str("payload_json"))
        val number = if (res.isNull("official_number")) null else res.getString("official_number")
        if (verb == "create") {
            sql.execute("INSERT OR REPLACE INTO entities(entity, entity_id, version, deleted, payload_json, official_number) VALUES(?,?,?,0,?,?)",
                entity, entityId, res.getLong("entity_version"), payload.toString(), number)
        } else if (cur != null) {
            val old = JSONObject(cur.str("payload_json"))
            val merged = if (verb == "update") merge(old, payload) else old
            sql.execute("UPDATE entities SET version=?, deleted=?, payload_json=? WHERE entity=? AND entity_id=?",
                res.getLong("entity_version"), if (verb == "delete") 1 else 0, merged.toString(), entity, entityId)
        }
    }

    private fun pull(rep: SyncReport) {
        while (true) {
            val cursor = store.getMeta("cursor")?.toLong() ?: 0L
            val data = request("GET", "/v1/sync/pull?app_id=${config.appId}&since=$cursor&limit=200").json()
            val changes = data.getJSONArray("changes")
            sql.transaction {
                for (i in 0 until changes.length()) {
                    val ch = changes.getJSONObject(i)
                    val entity = ch.getString("entity"); val entityId = ch.getString("entity_id")
                    val version = ch.getLong("entity_version")
                    val cur = sql.queryOne("SELECT version, payload_json FROM entities WHERE entity=? AND entity_id=?", entity, entityId)
                    if (cur != null && cur.long("version") > version) continue
                    val payload = if (!ch.isNull("payload")) ch.getJSONObject("payload")
                    else if (cur != null) JSONObject(cur.str("payload_json")) else JSONObject()
                    val number = if (ch.isNull("official_number")) null else ch.getString("official_number")
                    sql.execute("INSERT OR REPLACE INTO entities(entity, entity_id, version, deleted, payload_json, official_number) VALUES(?,?,?,?,?,?)",
                        entity, entityId, version, if (ch.getBoolean("deleted")) 1 else 0, payload.toString(), number)
                    rep.pulled++
                }
                store.setMeta("cursor", data.getLong("next_cursor"))
            }
            if (!data.getBoolean("has_more")) return
        }
    }

    // ------------------------------------------------- conflits, états, observation
    /** Opérations rejetées ou en conflit, non résolues : conservées jusqu'à décision de l'utilisateur. */
    fun attention(): List<AttentionItem> = sql.query(
        "SELECT op_id, type, entity, entity_id, state, last_error, result_json FROM outbox WHERE state IN ('REJECTED','CONFLICT') AND resolved=0 ORDER BY seq")
        .map {
            val res = it.str("result_json")?.let { j -> JSONObject(j) }
            AttentionItem(it.str("op_id")!!, it.str("type")!!, it.str("entity")!!, it.str("entity_id")!!, it.str("state")!!,
                it.str("last_error"), res?.optString("detail", null))
        }

    /**
     * Décision de l'utilisateur sur une opération rejetée/en conflit. keepMine=true : renvoie la modification
     * sur la version serveur courante (après une synchronisation) ; false : l'abandonne. Retourne le nouvel op_id.
     */
    fun resolve(opId: String, keepMine: Boolean): String? {
        val row = sql.queryOne("SELECT * FROM outbox WHERE op_id=? AND state IN ('REJECTED','CONFLICT') AND resolved=0", opId)
            ?: throw PfUsageException("Opération non résoluble")
        sql.execute("UPDATE outbox SET resolved=1 WHERE op_id=?", opId)
        notifyStatus(true)
        if (!keepMine) return null
        val verb = row.str("type")!!.substringAfterLast('.')
        val num = row.str("numbering_json")?.let { JSONObject(it) }?.let { Numbering(it.getString("series"), if (it.has("year")) it.getInt("year") else null) }
        val files = row.str("files_json")?.let { j -> JSONArray(j).let { a -> (0 until a.length()).map { i -> a.getString(i) } } } ?: emptyList()
        return enqueue(verb, row.str("entity")!!, row.str("entity_id")!!, JSONObject(row.str("payload_json")), num, files)
    }

    fun status(): SyncStatus {
        val ops = sql.query("SELECT state, COUNT(*) AS n FROM outbox GROUP BY state").associate { it.str("state")!! to it.long("n").toInt() }
        val files = sql.query("SELECT state, COUNT(*) AS n FROM files GROUP BY state").associate { it.str("state")!! to it.long("n").toInt() }
        val pending = (ops["PENDING"] ?: 0) + (ops["SENDING"] ?: 0) + (ops["WAITING_FILE"] ?: 0)
        val unresolved = sql.queryOne("SELECT COUNT(*) AS n FROM outbox WHERE state IN ('REJECTED','CONFLICT') AND resolved=0")!!.long("n").toInt()
        val failedFiles = files["FAILED"] ?: 0
        val attention = unresolved + failedFiles
        val enrolled = isEnrolled
        val lastSync = store.getMeta("last_sync_at")?.toLongOrNull()
        val level = when {
            !enrolled -> SyncLevel.NOT_ENROLLED
            store.getMeta("auth_state") == "revoked" -> SyncLevel.REVOKED
            attention > 0 -> SyncLevel.ATTENTION
            syncing -> SyncLevel.SYNCING
            pending > 0 && store.getMeta("reachable") == "0" -> SyncLevel.OFFLINE
            pending > 0 || (files["LOCAL"] ?: 0) + (files["UPLOADING"] ?: 0) > 0 -> SyncLevel.PENDING
            store.getMeta("reachable") == null -> SyncLevel.UNKNOWN
            else -> SyncLevel.OK
        }
        val progress = sql.query("SELECT file_id, state, size, sent_bytes, last_error FROM files WHERE state IN ('LOCAL','UPLOADING','FAILED') ORDER BY rowid")
            .map { FileProgress(it.str("file_id")!!, it.str("state")!!, it.long("size"), it.long("sent_bytes"), it.str("last_error")) }
        return SyncStatus(
            level, enrolled, syncing, pending, ops["WAITING_FILE"] ?: 0, ops["ACKED"] ?: 0, ops["REJECTED"] ?: 0, ops["CONFLICT"] ?: 0,
            attention, (files["LOCAL"] ?: 0) + (files["UPLOADING"] ?: 0), failedFiles, files["COMPLETE"] ?: 0, progress,
            lastSync, store.getMeta("last_error"), store.getMeta("cursor")?.toLongOrNull() ?: 0L)
    }

    fun addStatusListener(l: SyncStatusListener) { listeners.add(l) }
    fun removeStatusListener(l: SyncStatusListener) { listeners.remove(l) }

    /** Avertit les observateurs (thread appelant). [force]=false : au plus toutes les 250 ms (progression des morceaux). */
    private fun notifyStatus(force: Boolean) {
        if (listeners.isEmpty()) return
        val t = System.nanoTime() / 1_000_000
        if (!force && t - lastNotify < 250) return
        lastNotify = t
        val s = try { status() } catch (_: Exception) { return }
        for (l in listeners) try { l.onStatusChanged(s) } catch (_: Exception) { }
    }

    companion object {
        /** Version du schéma de la base locale (migrations appliquées automatiquement à l'ouverture). */
        val SCHEMA_VERSION: Int get() = LocalStore.MIGRATIONS.size
        const val MAX_PAYLOAD_BYTES = 256 * 1024
        const val MAX_BATCH_BYTES = 800 * 1024   // serveur : corps JSON limité à 1 Mio
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
    }
}
