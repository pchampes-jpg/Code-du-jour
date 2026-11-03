package pf.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import pf.client.SyncCoordinator

/** Relance la synchronisation dès que le réseau revient (sans attendre les délais de réessai). */
class NetworkSyncTrigger(context: Context, private val coordinator: SyncCoordinator) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { coordinator.requestSync(ignoreBackoff = true) }
    }

    @Synchronized fun start() { if (!started) { cm.registerDefaultNetworkCallback(callback); started = true } }
    @Synchronized fun stop() { if (started) { try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) { }; started = false } }
}
