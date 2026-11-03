package pf.client

import org.json.JSONObject

/** Série de numérotation officielle attribuée par le serveur à la création (jamais calculée sur l'appareil). */
data class Numbering(val series: String, val year: Int? = null)

/** Niveau de synchronisation, ordonné par priorité d'affichage pour l'interface. */
enum class SyncLevel {
    NOT_ENROLLED,  // appareil non enrôlé
    REVOKED,       // appareil révoqué par l'administrateur : plus aucune synchronisation
    ATTENTION,     // opérations rejetées / en conflit non résolues, ou fichier en échec
    SYNCING,       // synchronisation en cours
    OFFLINE,       // des données attendent d'être envoyées et le serveur est injoignable
    PENDING,       // des données attendent d'être envoyées (serveur joignable)
    UNKNOWN,       // jamais synchronisé
    OK,            // à jour
}

data class FileProgress(val fileId: String, val state: String, val size: Long, val sentBytes: Long, val lastError: String?)

data class SyncStatus(
    val level: SyncLevel,
    val enrolled: Boolean,
    val syncing: Boolean,
    val pendingOps: Int,        // PENDING + SENDING + WAITING_FILE
    val waitingFileOps: Int,
    val ackedOps: Int,
    val rejectedOps: Int,
    val conflictOps: Int,
    val attentionCount: Int,    // rejetées + conflits non résolus + fichiers en échec
    val filesPending: Int,      // LOCAL + UPLOADING
    val filesFailed: Int,
    val filesComplete: Int,
    val filesInProgress: List<FileProgress>,
    val lastSyncAtMillis: Long?,
    val lastError: String?,     // NETWORK, TLS, DEVICE_REVOKED, ou code serveur
    val cursor: Long,
)

fun interface SyncStatusListener { fun onStatusChanged(status: SyncStatus) }

data class SyncReport(
    var reachable: Boolean = false,
    var acked: Int = 0,
    var rejected: Int = 0,
    var conflicts: Int = 0,
    var waitingFile: Int = 0,
    var pulled: Int = 0,
    var filesUploaded: Int = 0,
    var chunksSent: Int = 0,
    var error: String? = null,   // null = passe complète sans erreur
    var skipped: Boolean = false,
)

data class EntityView(
    val entity: String,
    val entityId: String,
    val payload: JSONObject,
    val version: Long,
    val officialNumber: String?,
    val pendingOps: Int,
)

data class AttentionItem(
    val opId: String,
    val type: String,
    val entity: String,
    val entityId: String,
    val state: String,        // REJECTED | CONFLICT
    val errorCode: String?,
    val detail: String?,
)
