package ch.etasystems.pirol.data.importer

import ch.etasystems.pirol.data.repository.DetectionUpdateLog
import ch.etasystems.pirol.data.repository.SessionFiles
import ch.etasystems.pirol.data.repository.SessionJson
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.data.repository.ShadowReplace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.OutputStream
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream

/** T255: Paket ist keine PIROL-Session (session.json fehlt oder ist unlesbar). */
class NotASessionException(msg: String = "Keine PIROL-Session") : Exception(msg)

/**
 * T255: Import einer PIROL-Session aus ZIP (Upload/Export, T233/T253) oder Ordner (reines JVM).
 * Ablauf: alle Dateien in einen Staging-Ordner, dann [finalizeStaged] (Zusatzdatei zusammenfuehren,
 * Session-ID bei Kollision neu), erst danach wird der Ordner unter der Session-ID sichtbar.
 */
object SessionPackageLogic {

    const val SESSION_JSON = "session.json"
    private const val MEMOS_DIR = "memos"
    private const val MAX_DEPTH = 2
    private val ID_SUFFIX = Regex("""^(.+)_[0-9A-Za-z]{6}$""")
    private val ID_ALLOWED = Regex("""[^0-9A-Za-z._-]""")

    /**
     * Relativen Eintragspfad pruefen (Zip-Slip): nur "datei" oder "unterordner/datei", keine absoluten Pfade,
     * kein "..", keine versteckten Namen (auch __MACOSX). null = ueberspringen.
     */
    fun safePath(raw: String): String? {
        var p = raw.replace('\\', '/')
        while (p.startsWith("./")) p = p.substring(2)
        if (p.isEmpty() || p.startsWith("/") || p.endsWith("/") || p.contains(':')) return null
        val parts = p.split('/')
        if (parts.size > MAX_DEPTH) return null
        if (parts.any { it.isEmpty() || it == ".." || it == "." || it.startsWith(".") || it.startsWith("__MACOSX") }) return null
        if (ShadowReplace.isShadowFile(parts.last())) return null  // Reste unterbrochener Ersetzungen (T240)
        return parts.joinToString("/")
    }

    /**
     * ZIP eines ganzen Ordners ("sessionId/session.json"): gemeinsames Praefix aus dem ersten Eintrag.
     * Unser eigenes ZIP (T233) hat keines; "memos/..." ist ein Unterordner der Session, kein Praefix.
     */
    fun prefixOf(firstEntry: String): String? {
        val p = firstEntry.replace('\\', '/').trimStart('/')
        val slash = p.indexOf('/')
        if (slash <= 0) return null
        val head = p.substring(0, slash)
        return if (head == MEMOS_DIR || head.startsWith(".") || head.startsWith("__MACOSX")) null else "$head/"
    }

    /** Ordnername sicher (FAT/SAF): nur Buchstaben, Ziffern, . _ - */
    fun sanitizeId(id: String): String = id.trim().replace(ID_ALLOWED, "_").trim('.').ifEmpty { "session" }

    /**
     * Session-ID fuer den Import: die Original-ID, wenn frei; sonst gleicher Zeitteil mit neuer Kennung
     * ("2026-05-01T05-00-00_ab12cd" -> "2026-05-01T05-00-00_<neu>"), so oft bis frei.
     */
    fun resolveSessionId(original: String, taken: (String) -> Boolean, newUuid6: () -> String): String {
        val clean = sanitizeId(original)
        if (!taken(clean)) return clean
        val base = ID_SUFFIX.find(clean)?.groupValues?.get(1) ?: clean
        repeat(100) {
            val candidate = base + "_" + newUuid6()
            if (!taken(candidate)) return candidate
        }
        throw IllegalStateException("Keine freie Session-ID")
    }

    /** Ersetzt "sessionId" in session.json; unbekannte Felder bleiben erhalten (neuere Versionen). */
    fun rewriteSessionId(json: String, newId: String): String {
        val obj = SessionJson.lenient.parseToJsonElement(json).jsonObject
        val updated = JsonObject(obj.toMutableMap().apply { put("sessionId", JsonPrimitive(newId)) })
        return SessionJson.pretty.encodeToString(JsonObject.serializer(), updated)
    }

    /** Metadaten aus session.json; wirft [NotASessionException] wenn fehlend/unlesbar. */
    fun readMetadata(files: SessionFiles): SessionMetadata {
        val text = files.readText(SESSION_JSON) ?: throw NotASessionException()
        val meta = runCatching { SessionJson.lenient.decodeFromString(SessionMetadata.serializer(), text) }
            .getOrNull() ?: throw NotASessionException("session.json unlesbar")
        if (meta.sessionId.isBlank()) throw NotASessionException("sessionId fehlt")
        return meta
    }

    /** Tagesordner aus dem Session-Start (Ortszeit wie geschrieben); null = nicht lesbar */
    fun dayDirOf(meta: SessionMetadata): String? =
        runCatching { OffsetDateTime.parse(meta.startedAt).toLocalDate().toString() }.getOrNull()

    /**
     * Staging-Ordner fertigstellen: Zusatzdatei der Detektions-Updates (T251) in detections.jsonl uebernehmen,
     * bei neuer ID session.json umschreiben. @return Metadaten mit der endgueltigen ID
     */
    fun finalizeStaged(files: SessionFiles, finalId: String): SessionMetadata {
        val meta = readMetadata(files)
        if (files.exists(DetectionUpdateLog.FILE)) DetectionUpdateLog.consolidate(files)
        if (meta.sessionId != finalId) {
            val text = files.readText(SESSION_JSON) ?: throw NotASessionException()
            files.writeReplacing(SESSION_JSON, rewriteSessionId(text, finalId))
        }
        return meta.copy(sessionId = finalId)
    }

    /**
     * ZIP streamend entpacken (kein Zwischenspeicher): jeder gueltige Eintrag -> [open] (relativer Pfad).
     * @param checkCancel wirft bei Abbruch
     * @param onBytes gelesene (unkomprimierte) Bytes fuer den Fortschritt
     * @return Anzahl geschriebener Dateien
     */
    fun extractZip(
        input: InputStream,
        open: (String) -> OutputStream,
        checkCancel: () -> Unit = {},
        onBytes: (Long) -> Unit = {}
    ): Int {
        var count = 0
        var prefix: String? = null
        var first = true
        val buf = ByteArray(64 * 1024)
        ZipInputStream(input).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                checkCancel()
                if (first) { prefix = prefixOf(e.name); first = false }
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/').let { n -> prefix?.let { if (n.startsWith(it)) n.removePrefix(it) else n } ?: n }
                val path = safePath(name) ?: continue
                open(path).use { os ->
                    while (true) {
                        val r = zis.read(buf)
                        if (r < 0) break
                        os.write(buf, 0, r)
                        onBytes(r.toLong())
                        checkCancel()
                    }
                }
                count++
            }
        }
        return count
    }
}
