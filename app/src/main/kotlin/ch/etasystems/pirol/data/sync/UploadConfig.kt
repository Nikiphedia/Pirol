package ch.etasystems.pirol.data.sync

/** Art des Upload-Ziels. OFF = kein Netzwerk-/Ordner-Ziel (Standard). */
enum class UploadTargetType {
    OFF, FOLDER, SMB;

    companion object {
        fun fromName(name: String?): UploadTargetType =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** Verbindungsdaten fuer ein NAS (SMB-Freigabe). Das Passwort liegt NICHT hier, sondern im UploadCredentialStore. */
data class SmbSettings(
    val host: String,
    val share: String,
    val path: String,
    val user: String
)

/** Konfigurationsfehler (nicht wiederholbar, kein Retry). */
class UploadConfigException(message: String) : Exception(message)

/** Reine Logik (ohne Android/Netzwerk) fuer Validierung und Pfadbau. Unit-getestet. */
object UploadConfig {

    private val ILLEGAL_SEGMENT_CHARS = charArrayOf('<', '>', ':', '"', '|', '?', '*')

    /** Fehlermeldungen (Deutsch) fuer eine SMB-Konfiguration; leere Liste = gueltig. */
    fun validateSmb(settings: SmbSettings, password: String?): List<String> {
        val errors = mutableListOf<String>()
        val host = settings.host.trim()
        if (host.isEmpty()) {
            errors += "Host fehlt"
        } else if (host.any { it.isWhitespace() || it == '/' || it == '\\' }) {
            errors += "Host darf keine Leerzeichen oder Schraegstriche enthalten"
        }
        val share = settings.share.trim()
        if (share.isEmpty()) {
            errors += "Freigabe (Share) fehlt"
        } else if (share.any { it == '/' || it == '\\' || it in ILLEGAL_SEGMENT_CHARS }) {
            errors += "Freigabe enthaelt ungueltige Zeichen"
        }
        if (settings.user.trim().isEmpty()) errors += "Benutzer fehlt"
        if (password.isNullOrEmpty()) errors += "Passwort fehlt"
        if (settings.path.isNotBlank() && normalizeFolderPath(settings.path) == null) {
            errors += "Pfad enthaelt ungueltige Zeichen"
        }
        return errors
    }

    /**
     * Normalisiert einen Ordnerpfad ("a\b//c/" -> "a/b/c"). Leer erlaubt (Share-Wurzel).
     * Gibt null zurueck, wenn ein Segment ungueltige Zeichen enthaelt. "." und ".." werden verworfen (kein Ausbruch).
     */
    fun normalizeFolderPath(path: String): String? {
        val segments = path.split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        if (segments.any { seg -> seg.any { it in ILLEGAL_SEGMENT_CHARS || it.isISOControl() } }) return null
        return segments.joinToString("/")
    }

    /** Dateiname ohne Pfadtrenner und ungueltige Zeichen. */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.map { c ->
            if (c == '/' || c == '\\' || c in ILLEGAL_SEGMENT_CHARS || c.isISOControl()) '_' else c
        }.joinToString("").trim().trimStart('.')
        return cleaned.ifEmpty { "session.zip" }
    }

    /** Relativer Zielpfad innerhalb der Freigabe: `{ordner}/{datei}` bzw. `{datei}`. */
    fun buildRemotePath(folderPath: String, fileName: String): String {
        val folder = normalizeFolderPath(folderPath)
            ?: throw UploadConfigException("Pfad enthaelt ungueltige Zeichen")
        val file = sanitizeFileName(fileName)
        return if (folder.isEmpty()) file else "$folder/$file"
    }

    /** Teilt "DOMAIN\\user" in (Domain, Benutzer); ohne Domain ist diese leer. */
    fun splitUser(user: String): Pair<String, String> {
        val u = user.trim()
        val i = u.indexOf('\\')
        return if (i > 0) u.substring(0, i) to u.substring(i + 1) else "" to u
    }

    /** Verstaendliche deutsche Fehlermeldung; enthaelt nie Passwoerter. */
    fun describeError(t: Throwable): String {
        val msg = t.message ?: t.javaClass.simpleName
        return when {
            t is UploadConfigException -> msg
            msg.contains("LOGON_FAILURE", true) || msg.contains("Authentication failed", true) ->
                "Anmeldung fehlgeschlagen (Benutzer/Passwort)"
            msg.contains("BAD_NETWORK_NAME", true) -> "Freigabe nicht gefunden"
            msg.contains("ACCESS_DENIED", true) -> "Zugriff verweigert"
            t is java.net.UnknownHostException -> "Host nicht gefunden"
            t is java.net.ConnectException || t is java.net.SocketTimeoutException -> "NAS nicht erreichbar"
            else -> msg.take(160)
        }
    }
}
