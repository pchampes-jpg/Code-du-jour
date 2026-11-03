package pf.client

/** Échec réseau ou TLS : la requête a pu ou non atteindre le serveur. L'idempotence rend le renvoi sûr. */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** true = le certificat ou le nom d'hôte du serveur a été rejeté (à distinguer d'une simple panne réseau). */
    val isTlsRejection: Boolean get() = cause is javax.net.ssl.SSLException
}

/** Réponse d'erreur du serveur (RFC 7807). [code] est l'un des codes stables du contrat. */
class RemoteException(
    val status: Int,
    val code: String,
    val detail: String = "",
    val retryable: Boolean = false,
) : Exception("$status $code $detail".trim())

/** Erreur d'usage de la bibliothèque (entité inconnue, fichier inconnu, état incompatible...). */
class PfUsageException(message: String) : IllegalStateException(message)
