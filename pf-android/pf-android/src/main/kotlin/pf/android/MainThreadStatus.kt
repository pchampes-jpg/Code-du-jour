package pf.android

import android.os.Handler
import android.os.Looper
import pf.client.PlatformClient
import pf.client.SyncStatus
import pf.client.SyncStatusListener

/** Observation de l'état de synchronisation avec livraison sur le thread principal (prête pour l'interface). */
fun PlatformClient.addMainThreadStatusListener(listener: SyncStatusListener): SyncStatusListener {
    val handler = Handler(Looper.getMainLooper())
    val wrapper = SyncStatusListener { s: SyncStatus -> handler.post { listener.onStatusChanged(s) } }
    addStatusListener(wrapper)
    return wrapper // à passer à removeStatusListener() quand l'écran est détruit
}
