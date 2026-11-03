package pf.client

import org.json.JSONObject

class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun json(): JSONObject = JSONObject(String(body, Charsets.UTF_8))
    fun jsonOrNull(): JSONObject? = if (body.isEmpty()) null else try { json() } catch (e: Exception) { null }
}

/** Couche réseau. Lève [TransportException] en cas d'échec réseau/TLS ; les statuts HTTP d'erreur sont des réponses normales. */
interface Transport {
    fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): HttpResponse
}
