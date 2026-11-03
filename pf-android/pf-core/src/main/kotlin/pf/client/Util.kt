package pf.client

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

internal object Util {
    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Horodatage ISO 8601 UTC à la milliseconde, forme attendue par le contrat (ex. 2026-11-03T10:00:00.123Z). */
    fun iso(millis: Long): String = ISO.format(Instant.ofEpochMilli(millis))

    fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
    fun b64decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(Character.forDigit((b.toInt() shr 4) and 15, 16)).append(Character.forDigit(b.toInt() and 15, 16))
        return sb.toString()
    }

    fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    /** Empreinte en flux : jamais le fichier entier en mémoire. */
    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    val MIME_RE = Regex("^[a-z0-9][a-z0-9.+-]{0,62}/[a-z0-9][a-z0-9.+-]{0,62}$")
    val ENTITY_RE = Regex("^[a-z][a-z0-9_]{1,47}$")
    val ENTITY_ID_RE = Regex("^[A-Za-z0-9_.:-]{1,128}$")
}
