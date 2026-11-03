package pf.android

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Exécute une passe de synchronisation en arrière-plan. Si le processus vient d'être recréé, l'Application
 * doit avoir appelé PlatformRuntime.install() dans onCreate() (voir sample).
 */
class PfSyncJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private var running: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val handle = PlatformRuntime.handleOrNull()
        if (handle == null) { jobFinished(params, false); return false }
        running = executor.submit {
            val retry = try {
                val rep = handle.coordinator.syncNow(ignoreBackoff = true)
                rep.error == "NETWORK" || rep.error == "SERVER_BUSY"
            } catch (_: Throwable) { true }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel(true)
        return true // replanifié
    }
}
