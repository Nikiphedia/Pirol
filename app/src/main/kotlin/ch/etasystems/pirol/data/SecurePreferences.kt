package ch.etasystems.pirol.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Verschluesselte SharedPreferences fuer API-Keys und andere Secrets.
 * Nutzt AndroidX Security Crypto (AES256-GCM fuer Werte, AES256-SIV fuer Keys).
 *
 * NICHT fuer regulaere Settings verwenden — dafuer gibt es AppPreferences.
 */
class SecurePreferences(context: Context) {

    companion object {
        private const val TAG = "SecurePrefs"
        private const val FILE_NAME = "pirol_secure_prefs"
        private const val KEY_XENO_CANTO_API_KEY = "xeno_canto_api_key"
    }

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        Log.i(TAG, "SecurePreferences initialisiert")
    }

    /** Xeno-Canto API-Key (leer = nicht konfiguriert) */
    var xenoCantoApiKey: String
        get() = prefs.getString(KEY_XENO_CANTO_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_XENO_CANTO_API_KEY, value).apply()

    /** Prueft ob ein Xeno-Canto API-Key konfiguriert ist */
    val hasXenoCantoApiKey: Boolean
        get() = xenoCantoApiKey.isNotBlank()
}
