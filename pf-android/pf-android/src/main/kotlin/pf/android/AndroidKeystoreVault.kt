package pf.android

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import pf.client.SecretVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Enveloppe la graine Ed25519 de l'appareil avec une clé AES-256-GCM non exportable d'Android Keystore
 * (matériel sécurisé / StrongBox quand l'appareil en dispose). Aucune authentification utilisateur n'est exigée :
 * la synchronisation en arrière-plan doit fonctionner écran verrouillé.
 *
 * La clé ne quitte jamais le Keystore : une copie de la base (sauvegarde, adb, restauration sur un autre appareil)
 * ne permet pas de relire la graine. L'appareil doit alors être ré-enrôlé (erreur DEVICE_KEY_UNUSABLE).
 * Recommandation : android:allowBackup="false" dans l'application.
 */
class AndroidKeystoreVault(private val alias: String = "pf_device_wrap_v1") : SecretVault {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setIsStrongBoxBacked(true)
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(b.build()) }.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain) // IV de 12 octets généré par le Keystore
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_LEN + 16) { "graine enveloppée invalide" }
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped, 0, IV_LEN))
        }
        return c.doFinal(wrapped, IV_LEN, wrapped.size - IV_LEN)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
