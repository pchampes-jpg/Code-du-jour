package pf.client

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Paire de clés Ed25519 de l'appareil : la graine (32 octets) est le secret, à ne jamais journaliser. */
class DeviceKey private constructor(private val seed: ByteArray) {
    val publicKey: ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun seedBytes(): ByteArray = seed.copyOf()

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): DeviceKey {
            val s = ByteArray(32).also { random.nextBytes(it) }
            return DeviceKey(s)
        }
        fun fromSeed(seed: ByteArray): DeviceKey {
            require(seed.size == 32) { "graine Ed25519 de 32 octets attendue" }
            return DeviceKey(seed.copyOf())
        }
    }
}

/**
 * Protège la graine au repos. Sur Android : clé AES-GCM non exportable d'Android Keystore (pf-android).
 * La base SQLite ne contient que la graine enveloppée, inutilisable hors de l'appareil.
 */
interface SecretVault {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Coffre logiciel en mémoire : TESTS ET OUTILS JVM UNIQUEMENT. La clé d'enveloppe vit en RAM et disparaît avec le
 * processus ; ce n'est pas une protection d'appareil.
 */
class SoftwareVault(private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : SecretVault {
    private val random = SecureRandom()
    override fun wrap(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }
    override fun unwrap(wrapped: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12))) }
        return c.doFinal(wrapped, 12, wrapped.size - 12)
    }
}
