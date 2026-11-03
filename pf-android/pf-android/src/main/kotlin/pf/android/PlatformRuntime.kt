package pf.android

import android.content.Context
import pf.client.ClientConfig
import pf.client.HttpsTransport
import pf.client.PlatformClient
import pf.client.SecretVault
import pf.client.SyncCoordinator
import java.io.File

/**
 * Paramètres d'une application. [caPem] : autorité de certification interne (PEM) du serveur, embarquée dans
 * l'application (certificat PUBLIC, pas un secret) ; null = autorités du système.
 */
class PlatformOptions(
    val appId: String,
    val baseUrl: String,
    val caPem: ByteArray? = null,
    val periodicMinutes: Long = 15,
    val chunkSize: Int = 256 * 1024,
    val vault: SecretVault? = null,        // null = Android Keystore
)

class PlatformHandle(val client: PlatformClient, val coordinator: SyncCoordinator, val network: NetworkSyncTrigger)

/** Assemblage standard : base locale, coffre Keystore, HTTPS vérifié, synchronisation automatique et d'arrière-plan. */
object PlatformRuntime {
    @Volatile private var handle: PlatformHandle? = null

    /** À appeler dans Application.onCreate() (idempotent). */
    @Synchronized fun install(context: Context, options: PlatformOptions): PlatformHandle {
        handle?.let { return it }
        val app = context.applicationContext
        val dir = File(app.filesDir, "pf").apply { mkdirs() }
        val client = PlatformClient(
            ClientConfig(options.appId, File(dir, "files"), chunkSize = options.chunkSize),
            AndroidSqlDb(File(dir, "pf.db")),
            HttpsTransport(options.baseUrl, options.caPem),
            options.vault ?: AndroidKeystoreVault("pf_device_wrap_${options.appId}"))
        val coordinator = SyncCoordinator(client)
        val network = NetworkSyncTrigger(app, coordinator).also { it.start() }
        SyncScheduler.schedulePeriodic(app, options.periodicMinutes)
        return PlatformHandle(client, coordinator, network).also { handle = it }
    }

    fun handleOrNull(): PlatformHandle? = handle
    fun handle(): PlatformHandle = handle ?: error("PlatformRuntime.install() n'a pas été appelé")
}
