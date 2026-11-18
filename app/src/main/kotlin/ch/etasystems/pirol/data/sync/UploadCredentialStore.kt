package ch.etasystems.pirol.data.sync

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Speichert das NAS-Passwort verschluesselt (EncryptedSharedPreferences, Master-Key im Android Keystore).
 * Das Passwort wird nie im Klartext abgelegt und nie geloggt. Ist der Keystore nicht nutzbar,
 * wird NICHT in Klartext ausgewichen: Speichern schlaegt dann fehl.
 */
class UploadCredentialStore(private val context: Context) {

    private companion object {
        const val TAG = "UploadCredentialStore"
        const val FILE = "pirol_upload_secrets"
        const val KEY_SMB_PASSWORD = "smb_password"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getSmbPassword(): String? = try {
        prefs.getString(KEY_SMB_PASSWORD, null)
    } catch (e: Exception) {
        Log.e(TAG, "Passwort lesen fehlgeschlagen: ${e.javaClass.simpleName}")
        null
    }

    fun hasSmbPassword(): Boolean = !getSmbPassword().isNullOrEmpty()

    /** @return true bei Erfolg */
    fun setSmbPassword(password: String): Boolean = try {
        prefs.edit().putString(KEY_SMB_PASSWORD, password).commit()
    } catch (e: Exception) {
        Log.e(TAG, "Passwort speichern fehlgeschlagen: ${e.javaClass.simpleName}")
        false
    }

    fun clearSmbPassword() {
        try { prefs.edit().remove(KEY_SMB_PASSWORD).commit() } catch (_: Exception) { }
    }
}
