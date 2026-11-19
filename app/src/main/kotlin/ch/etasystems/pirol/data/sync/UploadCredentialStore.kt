package ch.etasystems.pirol.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Abstraktion der Verschluesselung (Keystore in Produktion, Fake in Tests). */
interface SecretCipher {
    /** @return IV + Ciphertext (Format siehe [SecretCodec]) */
    fun encrypt(plain: ByteArray): ByteArray

    /** @param packed IV + Ciphertext */
    fun decrypt(packed: ByteArray): ByteArray
}

/** Reine Kodierung: Paket = [1 Byte IV-Laenge][IV][Ciphertext], Base64 (NO_WRAP-kompatibel, ohne Android-Klassen). */
object SecretCodec {
    fun pack(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size in 1..255) { "IV-Laenge ungueltig" }
        return ByteArray(1 + iv.size + ciphertext.size).also {
            it[0] = iv.size.toByte()
            iv.copyInto(it, 1)
            ciphertext.copyInto(it, 1 + iv.size)
        }
    }

    /** @return Paar (IV, Ciphertext) */
    fun unpack(packed: ByteArray): Pair<ByteArray, ByteArray> {
        require(packed.isNotEmpty()) { "Leeres Paket" }
        val ivLen = packed[0].toInt() and 0xFF
        require(ivLen > 0 && packed.size > 1 + ivLen) { "Paket ungueltig" }
        return packed.copyOfRange(1, 1 + ivLen) to packed.copyOfRange(1 + ivLen, packed.size)
    }

    fun toBase64(data: ByteArray): String = java.util.Base64.getEncoder().encodeToString(data)

    fun fromBase64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}

/** AES-256-GCM mit Schluessel im Android Keystore. Kein Klartext-Fallback. */
class KeystoreSecretCipher(private val alias: String = KEY_ALIAS) : SecretCipher {

    private companion object {
        const val KEY_ALIAS = "pirol_upload_key"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return SecretCodec.pack(c.iv, c.doFinal(plain))
    }

    override fun decrypt(packed: ByteArray): ByteArray {
        val (iv, ct) = SecretCodec.unpack(packed)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return c.doFinal(ct)
    }
}

/**
 * Speichert das NAS-Passwort verschluesselt (AES-256-GCM, Schluessel im Android Keystore,
 * Ciphertext+IV als Base64 in normalen SharedPreferences). Das Passwort wird nie im Klartext
 * abgelegt und nie geloggt. Ist der Keystore nicht nutzbar, wird NICHT in Klartext
 * ausgewichen: Speichern schlaegt dann fehl.
 */
class UploadCredentialStore internal constructor(
    private val prefsProvider: () -> SharedPreferences,
    private val cipher: SecretCipher
) {

    constructor(context: Context) : this(
        { context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) },
        KeystoreSecretCipher()
    )

    internal companion object {
        const val TAG = "UploadCredentialStore"
        const val FILE = "pirol_upload_secrets_v2"
        const val KEY_SMB_PASSWORD = "smb_password"
    }

    private val prefs by lazy(prefsProvider)

    fun getSmbPassword(): String? = try {
        prefs.getString(KEY_SMB_PASSWORD, null)?.let {
            String(cipher.decrypt(SecretCodec.fromBase64(it)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        // Alte/ungueltige Werte werden wie "nicht gesetzt" behandelt
        logError("Passwort lesen fehlgeschlagen", e)
        null
    }

    fun hasSmbPassword(): Boolean = !getSmbPassword().isNullOrEmpty()

    /** @return true bei Erfolg */
    fun setSmbPassword(password: String): Boolean = try {
        val encoded = SecretCodec.toBase64(cipher.encrypt(password.toByteArray(Charsets.UTF_8)))
        prefs.edit().putString(KEY_SMB_PASSWORD, encoded).commit()
    } catch (e: Exception) {
        logError("Passwort speichern fehlgeschlagen", e)
        false
    }

    fun clearSmbPassword() {
        try { prefs.edit().remove(KEY_SMB_PASSWORD).commit() } catch (_: Exception) { }
    }

    private fun logError(msg: String, e: Exception) {
        try { Log.e(TAG, "$msg: ${e.javaClass.simpleName}") } catch (_: Throwable) { }
    }
}
