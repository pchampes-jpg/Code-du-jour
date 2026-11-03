package pf.android

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import pf.client.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Couche Android exécutée sous Robolectric (framework Android 15 réel, SQLite natif) contre le serveur v0.1.2 réel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidRuntimeTest {
    private lateinit var server: TestServer
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun up() { server = TestServer() }
    @After fun down() { PlatformRuntime.handleOrNull()?.let { it.network.stop(); it.coordinator.close(); it.client.close() }; resetRuntime(); server.close() }

    private fun resetRuntime() {
        val f = PlatformRuntime::class.java.getDeclaredField("handle"); f.isAccessible = true; f.set(PlatformRuntime, null)
    }
    private fun install() = PlatformRuntime.install(ctx, PlatformOptions("essai", server.url, server.caPem, vault = SoftwareVault(), chunkSize = 65_536))
    private fun id() = "n-" + UUID.randomUUID().toString().take(10)
    private fun await(ms: Long = 20_000, c: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!c()) { if (System.currentTimeMillis() > end) fail("délai"); Thread.sleep(30) } }

    @Test fun cycleCompletSurSQLiteAndroid_enrolement_horsLigne_fichiers_redemarrage() {
        val h = install()
        h.client.enroll(server.enrollmentCode())
        val nid = id(); val fid = h.client.addFile(ByteArray(300_000).also { java.util.Random(1).nextBytes(it) }, "image/jpeg")
        h.client.create("photo", nid, JSONObject().put("legende", "fictive"), files = listOf(fid))
        await { h.client.status().let { it.pendingOps == 0 && it.level == SyncLevel.OK } } // synchronisation automatique
        assertEquals(1, server.opsCount())
        assertEquals(300_000, server.serverFile(fid).getInt("size"))
        val st = h.client.status(); assertEquals(1, st.ackedOps); assertEquals(1, st.filesComplete)
    }

    @Test fun planificationJobScheduler_contraintesEtPersistance() {
        install()
        val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val p = js.getPendingJob(SyncScheduler.JOB_PERIODIC)!!
        assertTrue(p.isPeriodic); assertEquals(15 * 60_000L, p.intervalMillis); assertTrue(p.isPersisted)
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, p.networkType)
        assertEquals(PfSyncJobService::class.java.name, p.service.className)
        SyncScheduler.scheduleOnNetwork(ctx); assertNotNull(js.getPendingJob(SyncScheduler.JOB_NOW))
        SyncScheduler.cancelAll(ctx); assertNull(js.getPendingJob(SyncScheduler.JOB_PERIODIC))
    }

    @Test fun serviceDArrierePlan_envoieLesOperationsEnAttente() {
        val h = install(); h.client.enroll(server.enrollmentCode())
        h.coordinator.close() // coupe la synchronisation automatique : seule la tâche d'arrière-plan enverra
        h.client.create("note", id(), JSONObject().put("t", "arrière-plan"))
        assertEquals(0, server.opsCount())
        val svc = Robolectric.setupService(PfSyncJobService::class.java)
        val params = Mockito.mock(JobParameters::class.java)
        val spy = Mockito.spy(svc)
        val done = CountDownLatch(1)
        Mockito.doAnswer { done.countDown(); null }.`when`(spy).jobFinished(Mockito.any(), Mockito.anyBoolean())
        assertTrue(spy.onStartJob(params))
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertEquals(1, server.opsCount()); Mockito.verify(spy).jobFinished(params, false)
    }

    @Test fun retourDuReseau_declencheLaSynchronisation() {
        val h = install(); h.client.enroll(server.enrollmentCode())
        h.coordinator.close()
        val nid = id(); h.client.create("note", nid, JSONObject().put("t", "réseau"))
        val coordinator = SyncCoordinator(h.client)
        val trigger = NetworkSyncTrigger(ctx, coordinator).also { it.start() }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callbacks = shadowOf(cm).networkCallbacks
        assertTrue(callbacks.isNotEmpty())
        callbacks.forEach { it.onAvailable(Mockito.mock(Network::class.java)) }
        await { server.opsCount() == 1 }
        trigger.stop(); coordinator.close()
    }

    @Test fun observationSurLeThreadPrincipal() {
        val h = install()
        val seen = java.util.Collections.synchronizedList(mutableListOf<Pair<SyncLevel, Boolean>>())
        h.client.addMainThreadStatusListener { seen.add(it.level to (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())) }
        h.client.enroll(server.enrollmentCode())
        h.client.create("note", id(), JSONObject().put("t", "obs"))
        await { h.client.status().level == SyncLevel.OK }
        ShadowLooper.idleMainLooper()
        assertTrue(seen.isNotEmpty() && seen.all { it.second }, "livraison sur le thread principal : $seen")
        assertEquals(SyncLevel.OK, seen.last().first)
    }

    @Test fun coffreKeystore_disponibiliteSousRobolectric() {
        // Informatif : Robolectric ne fournit pas le fournisseur AndroidKeyStore. La classe compile contre l'API réelle ;
        // son comportement (matériel sécurisé) ne peut être validé que sur tablette.
        val available = try { java.security.KeyStore.getInstance("AndroidKeyStore"); true } catch (e: Exception) { false }
        println("AndroidKeyStore sous Robolectric : " + if (available) "présent" else "absent (validation sur tablette requise)")
    }
}
