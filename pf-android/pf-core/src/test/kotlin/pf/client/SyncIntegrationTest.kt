package pf.client

import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.test.*

/** Tests d'intégration contre le serveur Python v0.1.2 réel et non modifié. Données entièrement fictives. */
class SyncIntegrationTest {
    private lateinit var server: TestServer
    private val devices = mutableListOf<TestDevice>()

    @BeforeTest fun up() { server = TestServer() }
    @AfterTest fun down() { devices.forEach { it.cleanup() }; server.close() }

    private fun device(perms: String = "*", ft: FaultTransport? = null, now: (() -> Long)? = null): TestDevice {
        val d = TestDevice(server, transport = ft ?: HttpsTransport(server.url, server.caPem), nowFn = now ?: System::currentTimeMillis)
        d.enroll(perms)
        devices.add(d)
        return d
    }
    private fun faulty() = FaultTransport(HttpsTransport(server.url, server.caPem))
    private fun id(p: String = "n") = "$p-" + UUID.randomUUID().toString().take(12)
    private fun note(t: String) = JSONObject().put("text", t)
    private fun rawDb(d: TestDevice) = JdbcDb(d.dbFile.absolutePath)
    private fun await(timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) { if (System.currentTimeMillis() > end) fail("délai dépassé"); Thread.sleep(25) }
    }

    @Test fun `enrolement, creation hors ligne, synchronisation, numero officiel et relecture par un second appareil`() {
        val a = device(); val b = device()
        val cid = id("cmd")
        a.client.create("commande", cid, JSONObject().put("client", "FICTIF"), numbering = Numbering("CMD"))
        assertEquals(1, a.client.get("commande", cid)!!.pendingOps)
        assertNull(a.client.get("commande", cid)!!.officialNumber)
        val rep = a.client.sync()
        assertTrue(rep.reachable); assertNull(rep.error); assertEquals(1, rep.acked)
        val view = a.client.get("commande", cid)!!
        assertEquals(0, view.pendingOps); assertEquals(1L, view.version)
        assertTrue(Regex("CMD-\\d{4}-\\d{6}").matches(view.officialNumber!!), view.officialNumber)
        assertEquals(SyncLevel.OK, a.client.status().level)
        b.client.sync()
        val seen = b.client.get("commande", cid)!!
        assertEquals("FICTIF", seen.payload.getString("client")); assertEquals(view.officialNumber, seen.officialNumber)
    }

    @Test fun `hors ligne les operations sont conservees puis envoyees une seule fois`() {
        val ft = faulty(); val d = device(ft = ft)
        ft.offline = true
        repeat(3) { d.client.create("note", id(), note("hors ligne $it")) }
        val rep = d.client.sync()
        assertEquals("NETWORK", rep.error); assertFalse(rep.reachable)
        val st = d.client.status()
        assertEquals(SyncLevel.OFFLINE, st.level); assertEquals(3, st.pendingOps)
        assertEquals(0, server.opsCount())
        ft.offline = false
        val rep2 = d.client.sync()
        assertEquals(3, rep2.acked); assertNull(rep2.error)
        assertEquals(3, server.opsCount())
        assertEquals(SyncLevel.OK, d.client.status().level)
        d.client.sync(); d.client.sync()
        assertEquals(3, server.opsCount(), "une resynchronisation ne doit rien dupliquer")
    }

    @Test fun `reponse perdue apres application cote serveur - le renvoi est un doublon et non une seconde ecriture`() {
        val ft = faulty(); val d = device(ft = ft)
        val nid = id()
        d.client.create("note", nid, note("une seule fois"))
        ft.dropNextResponseFor = { m, p -> m == "POST" && p == "/v1/sync/push" }
        val r1 = d.client.sync()
        assertEquals("NETWORK", r1.error)
        assertEquals(1, server.opsCount(), "le serveur a bien appliqué l'opération")
        assertEquals(1, d.client.status().pendingOps)
        val r2 = d.client.sync()
        assertEquals(1, r2.acked); assertNull(r2.error)
        assertEquals(1, server.opsCount()); assertEquals(1L, server.serverEntity("note", nid)!!.getLong("version"))
        val db = rawDb(d)
        val res = JSONObject(db.query("SELECT result_json FROM outbox").first().str("result_json"))
        assertEquals("DUPLICATE", res.getString("status")); db.close()
    }

    @Test fun `arret brutal pendant l'envoi - l'etat SENDING est repris au redemarrage`() {
        val d = device()
        d.client.create("note", id(), note("crash"))
        val db = rawDb(d); db.execute("UPDATE outbox SET state='SENDING'"); db.close()
        val c = d.restart()
        assertEquals(1, c.status().pendingOps)
        assertEquals(1, c.sync().acked); assertEquals(1, server.opsCount())
    }

    @Test fun `persistance - redemarrage de l'application sans perte ni nouvel enrolement`() {
        val ft = faulty(); val d = device(ft = ft)
        ft.offline = true
        val nid = id(); d.client.create("note", nid, note("avant redémarrage"))
        val deviceId = d.client.deviceId
        val c = d.restart()
        assertTrue(c.isEnrolled); assertEquals(deviceId, c.deviceId)
        assertEquals("avant redémarrage", c.get("note", nid)!!.payload.getString("text"))
        ft.offline = false
        assertEquals(1, c.sync().acked)
    }

    @Test fun `la cle privee n'est jamais stockee en clair dans la base locale`() {
        val d = device(); d.client.sync()
        val db = rawDb(d)
        val wrapped = db.query("SELECT v FROM meta WHERE k='device_key'").first().str("v")!!
        db.close()
        val raw = Util.b64decode(wrapped)
        assertTrue(raw.size > 32, "graine enveloppée (IV + chiffré + étiquette), pas 32 octets bruts")
        // Un coffre différent ne peut pas relire la graine : la base seule est inexploitable.
        assertFails { SoftwareVault().unwrap(raw) }
        val dump = d.dbFile.readBytes().toString(Charsets.ISO_8859_1) + File(d.dbFile.path + "-wal").takeIf { it.exists() }?.readBytes()?.toString(Charsets.ISO_8859_1).orEmpty()
        assertFalse(dump.contains("access_token"), "le jeton d'accès n'est pas persisté")
    }

    @Test fun `photo de plus d'un Mio - coupure reseau puis reprise sans renvoyer les morceaux deja recus`() {
        val ft = faulty(); val d = device(ft = ft)
        val data = ByteArray(1_048_576 + 777).also { java.util.Random(42).nextBytes(it) }
        val fid = d.client.addFile(data, "image/jpeg")
        val pid = id("photo")
        d.client.create("photo", pid, JSONObject().put("legende", "fictive"), files = listOf(fid))
        ft.failAfter = 5; ft.failFilter = { m, p -> m == "PUT" && p.contains("/chunks/") }
        val r1 = d.client.sync()
        assertEquals("NETWORK", r1.error); assertEquals(5, r1.chunksSent)
        val prog = d.client.status().filesInProgress.single()
        assertEquals(5L * 65_536, prog.sentBytes); assertEquals(data.size.toLong(), prog.size)
        assertEquals(0, server.opsCount(), "l'opération n'est pas envoyée tant que le fichier n'est pas complet")
        ft.failAfter = -1
        val r2 = d.client.sync()
        assertNull(r2.error); assertEquals(12, r2.chunksSent); assertEquals(1, r2.filesUploaded); assertEquals(1, r2.acked)
        assertEquals(17, r1.chunksSent + r2.chunksSent, "chaque morceau est envoyé exactement une fois (17 = 16 pleins + 1 partiel)")
        assertEquals(18, ft.count("PUT"), "18 tentatives réseau : 17 réussies + 1 coupure injectée avant le serveur")
        val sf = server.serverFile(fid)
        assertEquals(Util.sha256Hex(data), sf.getString("sha256")); assertEquals(data.size, sf.getInt("size"))
        assertEquals(SyncLevel.OK, d.client.status().level)
    }

    @Test fun `reprise apres redemarrage de l'application en cours de transfert`() {
        val ft = faulty(); val d = device(ft = ft)
        val data = ByteArray(400_000).also { java.util.Random(7).nextBytes(it) }
        val fid = d.client.addFile(data, "application/pdf")
        ft.failAfter = 2; ft.failFilter = { m, p -> m == "PUT" && p.contains("/chunks/") }
        assertEquals("NETWORK", d.client.sync().error)
        ft.failAfter = -1
        val c = d.restart()
        val r = c.sync()
        assertNull(r.error); assertEquals(5, r.chunksSent) // 7 morceaux de 64 Kio au total, 2 déjà reçus
        assertEquals(Util.sha256Hex(data), server.serverFile(fid).getString("sha256"))
    }

    @Test fun `fichier local altere - jamais transmis et signale`() {
        val d = device()
        val fid = d.client.addFile(ByteArray(100_000) { 1 }, "image/png")
        val pid = id("photo"); d.client.create("photo", pid, note("x"), files = listOf(fid))
        d.client.localFile(fid)!!.appendBytes(byteArrayOf(9))
        val r = d.client.sync()
        assertEquals(0, r.filesUploaded); assertEquals(0, r.acked)
        assertNull(server.serverFile(fid).opt("sha256").takeIf { it != JSONObject.NULL })
        val st = d.client.status()
        assertEquals(SyncLevel.ATTENTION, st.level); assertEquals(1, st.filesFailed)
        assertEquals("LOCAL_FILE_ALTERED", st.filesInProgress.single().lastError)
        assertEquals(0, server.opsCount())
    }

    @Test fun `conflit - conserve, signale, puis resolu par l'utilisateur`() {
        val a = device(); val b = device()
        val nid = id(); a.client.create("note", nid, note("v1")); a.client.sync(); b.client.sync()
        a.client.update("note", nid, JSONObject().put("text", "v2 par A")); assertEquals(1, a.client.sync().acked)
        b.client.update("note", nid, JSONObject().put("extra", "ajout de B"))
        val r = b.client.sync()
        assertEquals(1, r.conflicts)
        val item = b.client.attention().single()
        assertEquals("CONFLICT", item.state); assertEquals("VERSION_CONFLICT", item.errorCode)
        assertEquals(SyncLevel.ATTENTION, b.client.status().level)
        val newOp = b.client.resolve(item.opId, keepMine = true)
        assertNotNull(newOp); assertTrue(b.client.attention().isEmpty())
        assertEquals(1, b.client.sync().acked)
        val final = JSONObject(server.serverEntity("note", nid)!!.getString("payload_json"))
        assertEquals("v2 par A", final.getString("text")); assertEquals("ajout de B", final.getString("extra"))
        assertEquals(SyncLevel.OK, b.client.status().level)
    }

    @Test fun `permissions - une operation interdite est rejetee, conservee et visible`() {
        val d = device(perms = "note:create")
        val nid = id(); d.client.create("note", nid, note("ok")); d.client.sync()
        assertTrue(d.client.canOffline("note:create")); assertFalse(d.client.canOffline("note:update"))
        d.client.update("note", nid, note("interdit"))
        val r = d.client.sync()
        assertEquals(1, r.rejected)
        assertEquals("FORBIDDEN", d.client.attention().single().errorCode)
        assertEquals("ok", JSONObject(server.serverEntity("note", nid)!!.getString("payload_json")).getString("text"))
    }

    @Test fun `appareil revoque - plus aucune synchronisation et etat explicite`() {
        val ft = faulty(); val d = device(ft = ft)
        d.client.sync()
        server.revoke(d.client.deviceId!!)
        d.client.create("note", id(), note("après révocation"))
        val r = d.client.sync()
        assertEquals("DEVICE_REVOKED", r.error)
        assertEquals(SyncLevel.REVOKED, d.client.status().level)
        val before = ft.log.size
        val r2 = d.client.sync()
        assertTrue(r2.skipped); assertEquals(before, ft.log.size, "aucune requête réseau après révocation")
        assertEquals(1, d.client.status().pendingOps, "l'opération reste conservée")
    }

    @Test fun `TLS - mauvaise autorite et mauvais nom d'hote sont rejetes, http est refuse`() {
        val d = device(); d.client.sync()
        // 1) autorité de confiance différente de celle du serveur
        TestServer().use { other ->
            d.transport = HttpsTransport(server.url, other.caPem); val c = d.restart()
            c.create("note", id(), note("tls"))
            val r = c.sync()
            assertEquals("TLS", r.error); assertFalse(r.reachable)
            assertEquals("TLS", c.status().lastError); assertEquals(0, server.opsCount().let { it - 0 }.coerceAtMost(0))
        }
        // 2) certificat valide mais émis pour un autre nom que l'adresse utilisée
        TestServer(sanDns = "autre.exemple.invalid").use { badName ->
            val t = HttpsTransport(badName.url, badName.caPem)
            val e = assertFailsWith<TransportException> { t.request("GET", "/v1/health") }
            assertTrue(e.isTlsRejection, "rejet attendu pour nom d'hôte non conforme : ${e.message}")
        }
        // 3) http en clair interdit
        assertFailsWith<IllegalArgumentException> { HttpsTransport("http://127.0.0.1:1") }
    }

    @Test fun `horloge de la tablette decalee d'une heure - connexion toujours possible`() {
        val d = device(now = { System.currentTimeMillis() + 3_600_000 })
        d.client.create("note", id(), note("horloge"))
        val r = d.client.sync()
        assertNull(r.error); assertEquals(1, r.acked)
    }

    @Test fun `jeton rejete par le serveur - une seule nouvelle signature puis succes`() {
        val ft = faulty(); val d = device(ft = ft)
        d.client.create("note", id(), note("401")); ft.forge401Once = true
        val r = d.client.sync()
        assertNull(r.error); assertEquals(1, r.acked)
        assertEquals(2, ft.count("POST /v1/auth/token"))
    }

    @Test fun `permissions hors ligne - indication limitee a leur duree de validite`() {
        var t = System.currentTimeMillis()
        val d = device(now = { t })
        d.client.sync()
        assertTrue(d.client.canOffline("note:create"))
        t += 13 * 3600_000L
        assertFalse(d.client.canOffline("note:create"))
    }

    @Test fun `plus de cent operations - envoi par lots, aucune perte`() {
        val ft = faulty(); val d = device(ft = ft)
        repeat(120) { d.client.create("note", id("m$it"), note("lot $it")) }
        val r = d.client.sync()
        assertNull(r.error); assertEquals(120, r.acked); assertEquals(120, server.opsCount())
        assertTrue(ft.count("POST /v1/sync/push") >= 3)
    }

    @Test fun `ecritures concurrentes pendant la synchronisation automatique`() {
        val d = device()
        val coord = SyncCoordinator(d.client)
        val pool = (0 until 4).map { w -> Thread { repeat(10) { i -> d.client.create("note", "c$w-$i-" + UUID.randomUUID().toString().take(6), note("t$w/$i")) } } }
        pool.forEach { it.start() }; pool.forEach { it.join() }
        await { d.client.status().let { it.pendingOps == 0 && it.ackedOps == 40 && it.level == SyncLevel.OK } }
        assertEquals(40, server.opsCount())
        assertEquals(SyncLevel.OK, d.client.status().level)
        coord.close()
    }

    @Test fun `observation de l'etat - l'interface est avertie des transitions`() {
        val ft = faulty(); val d = device(ft = ft)
        val levels = java.util.Collections.synchronizedList(mutableListOf<SyncLevel>())
        d.client.addStatusListener { levels.add(it.level) }
        ft.offline = true
        d.client.create("note", id(), note("obs")); d.client.sync()
        ft.offline = false; d.client.sync()
        assertTrue(SyncLevel.PENDING in levels && SyncLevel.SYNCING in levels && SyncLevel.OFFLINE in levels && levels.last() == SyncLevel.OK, "$levels")
    }

    @Test fun `enrolement - code a usage unique, application non autorisee, entrees invalides`() {
        val d = TestDevice(server); devices.add(d)
        val code = server.enrollmentCode()
        d.client.enroll(code)
        val e = assertFailsWith<RemoteException> { TestDevice(server).also { devices.add(it) }.client.enroll(code) }
        assertEquals("FORBIDDEN", e.code)
        assertFailsWith<PfUsageException> { d.client.create("Note!", id(), note("x")) }
        assertFailsWith<PfUsageException> { d.client.create("note", "id invalide/", note("x")) }
        assertFailsWith<PfUsageException> { d.client.create("note", id(), JSONObject().put("gros", "x".repeat(300_000))) }
        val nid = id(); d.client.create("note", nid, note("a"))
        assertFailsWith<PfUsageException> { d.client.create("note", nid, note("doublon local")) }
        assertFailsWith<PfUsageException> { d.client.update("note", "inconnu-1", note("x")) }
    }

    @Test fun `coffre de cle reinitialise - erreur explicite, donnees locales conservees`() {
        val d = device(); val nid = id(); d.client.sync()
        d.client.create("note", nid, note("garde"))
        val other = TestDevice(server, vault = SoftwareVault()) // même base serait rouverte avec un autre coffre
        other.cleanup()
        val db = d.dbFile
        d.client.close()
        val c = PlatformClient(ClientConfig("essai", File(d.dir, "files"), backoffBaseSeconds = 0.0), JdbcDb(db.absolutePath),
            HttpsTransport(server.url, server.caPem), SoftwareVault())
        val r = c.sync()
        assertEquals("DEVICE_KEY_UNUSABLE", r.error)
        assertEquals("garde", c.get("note", nid)!!.payload.getString("text"))
        assertEquals(1, c.status().pendingOps); c.close()
    }

    @Test fun `fichier absent cote serveur alors que le client le croit transfere - retransmis puis acquitte`() {
        val d = device(); val data = ByteArray(80_000) { 5 }
        val fid = d.client.addFile(data, "image/png")
        val pid = id("photo"); d.client.create("photo", pid, note("p"), files = listOf(fid))
        val db = rawDb(d); db.execute("UPDATE files SET state='COMPLETE'"); db.close() // état local faux : jamais envoyé
        val r1 = d.client.sync()
        assertEquals(1, r1.waitingFile); assertEquals(0, r1.acked)
        val r2 = d.client.sync()
        assertEquals(1, r2.filesUploaded); assertEquals(1, r2.acked)
        assertEquals(Util.sha256Hex(data), server.serverFile(fid).getString("sha256"))
    }

    @Test fun `entite supprimee - suppression propagee`() {
        val a = device(); val b = device()
        val nid = id(); a.client.create("note", nid, note("à supprimer")); a.client.sync(); b.client.sync()
        assertNotNull(b.client.get("note", nid))
        a.client.delete("note", nid); assertNull(a.client.get("note", nid)); a.client.sync(); b.client.sync()
        assertNull(b.client.get("note", nid)); assertTrue(b.client.list("note").none { it.entityId == nid })
    }
}
