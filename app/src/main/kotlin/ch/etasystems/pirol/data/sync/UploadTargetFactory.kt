package ch.etasystems.pirol.data.sync

import android.content.Context
import android.net.Uri
import ch.etasystems.pirol.data.AppPreferences

/** Baut aus den gespeicherten Einstellungen das konfigurierte Upload-Ziel. */
object UploadTargetFactory {

    /**
     * @return Ziel, oder null wenn kein Ziel konfiguriert ist (OFF).
     * @throws UploadConfigException bei unvollstaendiger Konfiguration
     */
    fun create(context: Context, prefs: AppPreferences, creds: UploadCredentialStore): UploadTarget? {
        return when (UploadTargetType.fromName(prefs.uploadTargetType)) {
            UploadTargetType.OFF -> null
            UploadTargetType.FOLDER -> {
                val uri = prefs.uploadFolderUri
                if (uri.isBlank()) throw UploadConfigException("Kein Zielordner gewaehlt")
                SafFolderTarget(context, Uri.parse(uri))
            }
            UploadTargetType.SMB -> {
                val settings = SmbSettings(prefs.smbHost, prefs.smbShare, prefs.smbPath, prefs.smbUser)
                val password = creds.getSmbPassword()
                val errors = UploadConfig.validateSmb(settings, password)
                if (errors.isNotEmpty()) throw UploadConfigException(errors.first())
                SmbTarget(settings, password!!)
            }
        }
    }

    /** true, wenn ein Ziel ausgewaehlt ist (Voraussetzung fuer automatischen Upload) */
    fun isConfigured(prefs: AppPreferences): Boolean =
        UploadTargetType.fromName(prefs.uploadTargetType) != UploadTargetType.OFF
}
