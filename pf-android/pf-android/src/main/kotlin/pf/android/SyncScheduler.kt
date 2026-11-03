package pf.android

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/** Synchronisation d'arrière-plan via JobScheduler (aucune dépendance supplémentaire), persistante après redémarrage. */
object SyncScheduler {
    const val JOB_PERIODIC = 0x5046_01   // "PF" + 01
    const val JOB_NOW = 0x5046_02

    /** Passe périodique quand un réseau est disponible (Android impose 15 minutes minimum). */
    fun schedulePeriodic(context: Context, intervalMinutes: Long = 15) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (js.getPendingJob(JOB_PERIODIC) != null) return
        val info = JobInfo.Builder(JOB_PERIODIC, ComponentName(context, PfSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(intervalMinutes * 60_000L)
            .setPersisted(true)
            .build()
        js.schedule(info)
    }

    /** Passe unique dès qu'un réseau est disponible (reprise garantie même si l'application est fermée). */
    fun scheduleOnNetwork(context: Context) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val info = JobInfo.Builder(JOB_NOW, ComponentName(context, PfSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        js.schedule(info)
    }

    fun cancelAll(context: Context) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        js.cancel(JOB_PERIODIC); js.cancel(JOB_NOW)
    }
}
