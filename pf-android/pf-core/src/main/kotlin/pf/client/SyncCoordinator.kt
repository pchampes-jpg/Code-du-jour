package pf.client

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronisation automatique : regroupe les demandes (enregistrement local, retour du réseau, tâche périodique)
 * en passes successives sur un seul thread. Une demande arrivée pendant une passe en déclenche une seconde ensuite.
 */
class SyncCoordinator(private val client: PlatformClient) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pf-sync").apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)
    @Volatile private var ignoreBackoffNext = false
    @Volatile var lastReport: SyncReport? = null
        private set

    init { client.onLocalChange = { requestSync() } }

    /** Demande asynchrone. [ignoreBackoff]=true au retour du réseau : on réessaie sans attendre les délais. */
    fun requestSync(ignoreBackoff: Boolean = false) {
        if (ignoreBackoff) ignoreBackoffNext = true
        if (queued.compareAndSet(false, true)) {
            executor.execute {
                queued.set(false)
                val ib = ignoreBackoffNext; ignoreBackoffNext = false
                try { lastReport = client.sync(ib) } catch (_: Throwable) { /* l'état reste consultable via status() */ }
            }
        }
    }

    /** Passe synchrone (tâche d'arrière-plan JobScheduler). */
    fun syncNow(ignoreBackoff: Boolean = true): SyncReport = client.sync(ignoreBackoff).also { lastReport = it }

    override fun close() { executor.shutdown(); client.onLocalChange = null }
}
