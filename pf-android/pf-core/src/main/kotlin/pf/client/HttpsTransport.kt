package pf.client

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Transport HTTPS.
 *  - Schéma https obligatoire : toute autre URL est refusée à la construction.
 *  - Le certificat est TOUJOURS vérifié, ainsi que le nom d'hôte (vérification par défaut de la plateforme).
 *    Aucune option ne permet de désactiver ces contrôles.
 *  - [trustedCaPem] : autorité de certification interne (PEM) à faire seule confiance pour ce serveur.
 *    null = magasin de confiance du système (certificat émis par une autorité publique).
 *  - TLS 1.2 minimum.
 */
class HttpsTransport(
    baseUrl: String,
    trustedCaPem: ByteArray? = null,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) : Transport {
    private val base: URL = URL(baseUrl.trimEnd('/'))
    private val sslContext: SSLContext

    init {
        require(base.protocol == "https") { "HTTPS obligatoire : $baseUrl" }
        require(!baseUrl.contains("@")) { "Identifiants dans l'URL interdits" }
        sslContext = SSLContext.getInstance("TLS")
        if (trustedCaPem == null) {
            sslContext.init(null, null, null)
        } else {
            val certs = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(trustedCaPem))
            require(certs.isNotEmpty()) { "Aucun certificat dans l'autorité fournie" }
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certs.forEachIndexed { i, c -> setCertificateEntry("pf-ca-$i", c) }
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            sslContext.init(null, tmf.trustManagers, null)
        }
    }

    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        require(path.startsWith("/")) { "chemin relatif attendu" }
        val conn: HttpsURLConnection
        try {
            conn = URL(base.protocol, base.host, base.port, base.path.trimEnd('/') + path).openConnection() as HttpsURLConnection
        } catch (e: IOException) {
            throw TransportException("OPEN:" + e.javaClass.simpleName, e)
        }
        try {
            conn.sslSocketFactory = TlsFactory(sslContext.socketFactory)
            // Le vérificateur de nom d'hôte par défaut est conservé (jamais remplacé).
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            val hdrs = HashMap<String, String>()
            conn.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) hdrs[k.lowercase()] = v.last() }
            return HttpResponse(status, hdrs, data)
        } catch (e: IOException) { // inclut SSLException, SocketTimeoutException, UnknownHostException, ConnectException
            throw TransportException(e.javaClass.simpleName, e)
        } finally {
            conn.disconnect()
        }
    }

    /** Impose TLS 1.2 minimum sur les sockets créés. */
    private class TlsFactory(private val d: javax.net.ssl.SSLSocketFactory) : javax.net.ssl.SSLSocketFactory() {
        private fun <T : java.net.Socket> t(s: T): T {
            if (s is javax.net.ssl.SSLSocket) {
                val allowed = s.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
                if (allowed.isNotEmpty()) s.enabledProtocols = allowed.toTypedArray()
            }
            return s
        }
        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
        override fun createSocket(s: java.net.Socket?, host: String?, port: Int, auto: Boolean) = t(d.createSocket(s, host, port, auto))
        override fun createSocket(host: String?, port: Int) = t(d.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, la: java.net.InetAddress?, lp: Int) = t(d.createSocket(host, port, la, lp))
        override fun createSocket(a: java.net.InetAddress?, port: Int) = t(d.createSocket(a, port))
        override fun createSocket(a: java.net.InetAddress?, port: Int, la: java.net.InetAddress?, lp: Int) = t(d.createSocket(a, port, la, lp))
    }
}
