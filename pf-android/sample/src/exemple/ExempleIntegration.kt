package exemple

import android.app.Application
import android.content.Context
import org.json.JSONObject
import pf.android.PlatformOptions
import pf.android.PlatformRuntime
import pf.android.addMainThreadStatusListener
import pf.client.Numbering
import pf.client.SyncLevel
import pf.client.SyncStatus
import java.io.File

/*
 * Intégration minimale de la bibliothèque dans une future APK (application fictive « exemple_app »).
 * Aucune interface graphique imposée : ces classes montrent les appels à brancher sur vos écrans.
 *
 * Prérequis côté serveur (administrateur) : l'application « exemple_app » est enregistrée avec ses entités
 * (python -m pf_server register-app manifeste.json) et un code d'enrôlement à usage unique est émis pour l'utilisateur.
 */

/** 1. Application : une seule ligne d'installation, idempotente, exécutée aussi quand le système recrée le processus. */
class ExempleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PlatformRuntime.install(
            this,
            PlatformOptions(
                appId = "exemple_app",
                baseUrl = "https://plateforme.exemple.invalid:8443",
                // Autorité de certification interne (certificat PUBLIC) embarquée dans assets/pf_ca.pem.
                caPem = assets.open("pf_ca.pem").use { it.readBytes() },
            ),
        )
    }
}

/** 2. Contrôleur d'écran : enrôlement, saisie hors ligne, photo, état de synchronisation. */
class ExempleControleur(private val context: Context) {
    private val client get() = PlatformRuntime.handle().client

    /** Écran d'enrôlement : le code saisi est un secret, ne jamais l'afficher ni le journaliser. */
    fun enroler(codeSaisi: String) = client.enroll(codeSaisi)

    /** Fonctionne sans réseau : l'enregistrement est durable immédiatement, l'envoi est automatique et idempotent. */
    fun saisirCommande(idLocal: String, nomClient: String): String =
        client.create("commande", idLocal, JSONObject().put("client", nomClient), numbering = Numbering("CMD"))

    /** Photo prise par l'appareil : copiée en flux dans le dossier privé, envoyée par morceaux avec reprise. */
    fun joindrePhoto(idCommande: String, photo: File) {
        val fileId = client.addFile(photo, "image/jpeg")
        client.update("commande", idCommande, JSONObject().put("photo", fileId), files = listOf(fileId))
    }

    /** Liste d'écran : données confirmées + modifications locales en attente (« pendingOps » > 0 = pas encore envoyée). */
    fun commandes() = client.list("commande")

    /** Autorisation d'afficher un bouton hors ligne (indication seulement ; le serveur décide toujours). */
    fun peutCreerHorsLigne() = client.canOffline("commande:create")

    /** Bandeau d'état, mis à jour sur le thread principal. À retirer à la destruction de l'écran. */
    fun observer(afficher: (String) -> Unit) =
        client.addMainThreadStatusListener { afficher(libelle(it)) }

    fun desinscrire(l: pf.client.SyncStatusListener) = client.removeStatusListener(l)

    /** Conflits / rejets à présenter à l'utilisateur : conservés, jamais écrasés en silence. */
    fun aVerifier() = client.attention()
    fun garderMaVersion(opId: String) = client.resolve(opId, keepMine = true)
    fun abandonner(opId: String) = client.resolve(opId, keepMine = false)

    /** Synchronisation manuelle (bouton « Synchroniser ») : en plus de l'automatique. */
    fun synchroniserMaintenant() = PlatformRuntime.handle().coordinator.requestSync(ignoreBackoff = true)

    companion object {
        fun libelle(s: SyncStatus): String = when (s.level) {
            SyncLevel.NOT_ENROLLED -> "Appareil non enrôlé"
            SyncLevel.REVOKED -> "Appareil révoqué : contacter l'administrateur"
            SyncLevel.ATTENTION -> "${s.attentionCount} élément(s) à vérifier"
            SyncLevel.SYNCING -> "Synchronisation en cours…"
            SyncLevel.OFFLINE -> "Hors ligne : ${s.pendingOps} opération(s) en attente"
            SyncLevel.PENDING -> "${s.pendingOps} opération(s) à envoyer"
            SyncLevel.UNKNOWN -> "Jamais synchronisé"
            SyncLevel.OK -> "À jour"
        } + s.filesInProgress.firstOrNull { it.state == "UPLOADING" || it.sentBytes > 0 }
            ?.let { " — fichier ${it.sentBytes * 100 / it.size} %" }.orEmpty()
    }
}
