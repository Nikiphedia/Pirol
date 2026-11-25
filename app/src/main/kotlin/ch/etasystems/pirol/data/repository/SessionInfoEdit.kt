package ch.etasystems.pirol.data.repository

import ch.etasystems.pirol.data.importer.TimeSource
import ch.etasystems.pirol.location.TrackLogic
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.rescan.RescanFiles
import ch.etasystems.pirol.util.parseInstantCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * T257: Bearbeitungsauftrag fuer Startzeit, Ort und Bemerkung einer importierten Session.
 * Wird vor dem ersten Schreiben als [SessionInfoEdit.JOURNAL_FILE] abgelegt; ein Abbruch wird damit beim
 * naechsten Oeffnen (oder vor der naechsten Bearbeitung) zu Ende gefuehrt, ohne doppelt zu verschieben.
 */
@Serializable
data class SessionInfoEditPlan(
    /** Verschiebung aller Wanduhr-Zeiten in ms (0 = Startzeit unveraendert) */
    val deltaMs: Long,
    /** Zone fuer neu geschriebene ISO-Zeiten (Lokalzeit mit Offset) */
    val zoneId: String,
    /** Ort geaendert (sonst werden Detektions-Positionen nicht angefasst) */
    val relocate: Boolean,
    /** Session-Ort vor der Bearbeitung (Erkennung "Position vom Session-Ort uebernommen") */
    val oldLatitude: Double? = null,
    val oldLongitude: Double? = null,
    /** Neuer Ort; null = entfernt */
    val newLatitude: Double? = null,
    val newLongitude: Double? = null,
    /** Neue session.json (wird zuletzt geschrieben) */
    val metadata: SessionMetadata,
    /** SHA-256 der alten session.json: nur ersetzen, solange sie noch unveraendert ist */
    val oldSessionJsonHash: String? = null,
    /** Dateiname -> SHA-256 des Inhalts vor der Bearbeitung; nur unveraenderte Dateien werden umgerechnet */
    val originalHashes: Map<String, String> = emptyMap(),
    /** T273: Ordner umbenennen/verschieben (neue Session-ID aus der Startzeit); null = Ordner bleibt */
    val rename: SessionRenameTarget? = null
)

/**
 * T257: Reine Umrechnung fuer "Angaben bearbeiten" (importierte Sessions). Kein Android, unit-testbar.
 *
 * Zeiten: alle Wanduhr-Felder um [SessionInfoEditPlan.deltaMs] verschieben; Audio-Offsets (chunkStartSec,
 * bestStartSec, Segment-startSec) bleiben. JSONL-Zeilen werden als JsonObject bearbeitet, damit unbekannte
 * Felder und die Feldreihenfolge erhalten bleiben; unlesbare Zeilen bleiben unveraendert.
 *
 * Ortsregel ([keepsOwnPosition]): eine Detektion behaelt ihre Position, wenn sie auf der Karte verschoben wurde
 * (POSITION_MOVED), einen eigenen GPS-Fix hat (locationAccuracyM gesetzt) oder eine Position traegt, die vom
 * alten Session-Ort abweicht (z.B. manueller Eintrag mit Geraeteposition, Rescan mit Route). Sonst (keine
 * Position, oder Position = alter Session-Ort ohne Genauigkeit, so setzen Import/Rescan sie) gilt der neue Ort.
 */
object SessionInfoEdit {

    /** Auftragsdatei im Session-Ordner (liegt nur waehrend bzw. nach Abbruch einer Bearbeitung) */
    const val JOURNAL_FILE = "session.edit.pending.json"

    /** Gleichheit von Koordinaten (Grad), ca. 0.1 m */
    private const val COORD_TOLERANCE = 1e-6

    private const val MERGE_TIME_PREFIX = "lastSeenAt:"

    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    // ── Zeit ─────────────────────────────────────────────────────────

    /** Differenz neuer minus alter Start in ms; null = alter Start nicht lesbar. */
    fun deltaMs(oldStartedAt: String, newStart: ZonedDateTime): Long? {
        val old = parseInstantCompat(oldStartedAt) ?: return null
        return newStart.toInstant().toEpochMilli() - old.toEpochMilli()
    }

    /**
     * ISO-Zeit um [deltaMs] verschieben, Ausgabe als Lokalzeit mit Offset in [zone] (Offset passt zum neuen
     * Zeitpunkt, auch ueber einen Sommer-/Winterzeit-Wechsel). Unlesbar oder delta 0: unveraendert.
     */
    fun shiftIso(iso: String, deltaMs: Long, zone: ZoneId): String {
        if (deltaMs == 0L) return iso
        val instant = parseInstantCompat(iso) ?: return iso
        return formatIso(instant.plusMillis(deltaMs), zone)
    }

    fun formatIso(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(ISO)

    /** CLUSTER_MERGED-Nutzlast: `lastSeenAt:<ms>` verschieben, alles andere unveraendert (alte Events ohne Feld ok). */
    fun shiftMergePayload(payload: String, deltaMs: Long): String {
        if (deltaMs == 0L || !payload.contains(MERGE_TIME_PREFIX)) return payload
        return payload.split('|').joinToString("|") { part ->
            if (!part.startsWith(MERGE_TIME_PREFIX)) return@joinToString part
            val ms = part.removePrefix(MERGE_TIME_PREFIX).trim().toLongOrNull() ?: return@joinToString part
            MERGE_TIME_PREFIX + (ms + deltaMs)
        }
    }

    /** Anzeige der Differenz, z.B. "+2 h 15 min", "-1 d 3 h", "0 min". */
    fun formatDelta(deltaMs: Long): String {
        val sign = if (deltaMs < 0) "-" else "+"
        var s = abs(deltaMs) / 1000
        val d = s / 86_400; s %= 86_400
        val h = s / 3_600; s %= 3_600
        val m = s / 60; s %= 60
        val parts = buildList {
            if (d > 0) add("$d d")
            if (h > 0) add("$h h")
            if (m > 0) add("$m min")
            if (s > 0) add("$s s")
        }
        return if (parts.isEmpty()) "0 min" else sign + parts.joinToString(" ")
    }

    // ── Plan ─────────────────────────────────────────────────────────

    /**
     * Neuer Auftrag aus der alten session.json und den Eingaben. [newLatitude]/[newLongitude] beide null = Ort
     * entfernen. Startzeit-Aenderung setzt [ImportedSource.timeSource] auf "manual".
     * [originalHashes] fuellt der Aufrufer (Dateien abhaengig vom Ordnerinhalt, siehe [filesToEdit]).
     */
    fun plan(
        old: SessionMetadata,
        newStart: ZonedDateTime,
        newLatitude: Double?,
        newLongitude: Double?,
        note: String?,
        zone: ZoneId
    ): SessionInfoEditPlan? {
        val delta = deltaMs(old.startedAt, newStart) ?: return null
        val lat = newLatitude?.takeIf { newLongitude != null }
        val lon = newLongitude?.takeIf { newLatitude != null }
        val relocate = !sameCoord(old.latitude, lat) || !sameCoord(old.longitude, lon)
        val manual = TimeSource.MANUAL.name.lowercase()
        val meta = old.copy(
            startedAt = if (delta == 0L) old.startedAt else formatIso(newStart.toInstant(), zone),
            endedAt = old.endedAt?.let { shiftIso(it, delta, zone) },
            recordingSegments = old.recordingSegments?.map { seg ->
                seg.copy(startedAt = seg.startedAt?.let { shiftIso(it, delta, zone) })
            },
            importedFrom = old.importedFrom?.map { src ->
                if (delta == 0L) src else src.copy(startedAt = shiftIso(src.startedAt, delta, zone), timeSource = manual)
            },
            latitude = if (relocate) lat else old.latitude,
            longitude = if (relocate) lon else old.longitude,
            note = note?.trim()?.ifEmpty { null }
        )
        return SessionInfoEditPlan(
            deltaMs = delta,
            zoneId = zone.id,
            relocate = relocate,
            oldLatitude = old.latitude,
            oldLongitude = old.longitude,
            newLatitude = lat,
            newLongitude = lon,
            metadata = meta
        )
    }

    /**
     * T273: Auftrag um die Ordner-Umbenennung ergaenzen: session.json bekommt die neue Session-ID (= neuer
     * Ordnername). [target] null = Auftrag unveraendert.
     */
    fun withRename(plan: SessionInfoEditPlan, target: SessionRenameTarget?): SessionInfoEditPlan =
        if (target == null) plan
        else plan.copy(rename = target, metadata = plan.metadata.copy(sessionId = target.newName))

    /** Muessen Dateien ausser session.json umgerechnet werden? */
    fun touchesFiles(plan: SessionInfoEditPlan): Boolean = plan.deltaMs != 0L || plan.relocate

    /**
     * Umzurechnende Dateien aus dem Ordnerinhalt: Original (Detektionen, Verifikationen), sichtbare
     * Rescan-Varianten (mit rescan-<id>.json) und track.jsonl. Raven/GPX entstehen danach neu.
     * Bei reinem Ortswechsel ohne Zeitverschiebung nur die Detektionsdateien.
     */
    fun filesToEdit(names: Collection<String>, plan: SessionInfoEditPlan): List<String> {
        if (!touchesFiles(plan)) return emptyList()
        val ids: List<String?> = listOf<String?>(null) + RescanFiles.idsIn(names)
        val out = mutableListOf<String>()
        for (id in ids) {
            out += RescanFiles.detections(id)
            if (plan.deltaMs != 0L) out += RescanFiles.verifications(id)
        }
        if (plan.deltaMs != 0L) out += TrackLogic.TRACK_FILE
        return out.filter { it in names }
    }

    /** Zugehoerige Verifikationsdatei einer Detektionsdatei (fuer POSITION_MOVED); null = keine Detektionsdatei. */
    fun verificationsFor(detectionsName: String): String? {
        if (detectionsName == RescanFiles.DETECTIONS) return RescanFiles.VERIFICATIONS
        val m = Regex("""detections\.rescan-(.+)\.jsonl""").matchEntire(detectionsName) ?: return null
        return RescanFiles.verifications(m.groupValues[1])
    }

    /**
     * Inhalt einer Datei nach dem Auftrag. [eventsText] = zugehoerige verifications (nur fuer Detektionsdateien,
     * POSITION_MOVED). Unbekannte Dateien bleiben unveraendert.
     */
    fun transform(name: String, text: String, plan: SessionInfoEditPlan, eventsText: String?): String {
        val zone = ZoneId.of(plan.zoneId)
        return when {
            verificationsFor(name) != null -> detectionsText(text, plan, movedIds(eventsText))
            name == RescanFiles.VERIFICATIONS || (name.startsWith("verifications.rescan-") && name.endsWith(".jsonl")) ->
                eventsText(text, plan.deltaMs)
            name == TrackLogic.TRACK_FILE -> trackText(text, plan.deltaMs, zone)
            else -> text
        }
    }

    // ── Ort ──────────────────────────────────────────────────────────

    /** IDs der auf der Karte verschobenen Detektionen (POSITION_MOVED). */
    fun movedIds(eventsText: String?): Set<String> {
        if (eventsText.isNullOrEmpty()) return emptySet()
        return SessionTextParser.events(eventsText)
            .filter { it.actionType == VerificationEvent.ACTION_POSITION_MOVED }
            .map { it.detectionId }
            .toSet()
    }

    /**
     * Ortsregel (siehe Klassenkommentar): true = Detektion behaelt ihre Position.
     */
    fun keepsOwnPosition(
        id: String,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Float?,
        oldLatitude: Double?,
        oldLongitude: Double?,
        movedIds: Set<String>
    ): Boolean {
        if (id in movedIds) return true
        if (latitude == null || longitude == null) return false
        if (accuracyM != null) return true
        if (oldLatitude == null || oldLongitude == null) return true
        return !(sameCoord(latitude, oldLatitude) && sameCoord(longitude, oldLongitude))
    }

    private fun sameCoord(a: Double?, b: Double?): Boolean =
        if (a == null || b == null) a == b else abs(a - b) <= COORD_TOLERANCE

    // ── Zeilen ───────────────────────────────────────────────────────

    /** detections.jsonl (oder Variante): Zeiten verschieben, Position nach der Ortsregel setzen. */
    fun detectionsText(text: String, plan: SessionInfoEditPlan, movedIds: Set<String>): String = mapObjects(text) { o ->
        val m = LinkedHashMap(o)
        val d = plan.deltaMs
        if (d != 0L) {
            shiftLong(m, "timestampMs", d)
            shiftLong(m, "firstSeenAtMs", d)
            shiftLong(m, "lastSeenAtMs", d)
            shiftLong(m, "lastDetectedMs", d, onlyPositive = true)
            shiftLong(m, "verifiedAtMs", d)
        }
        if (plan.relocate) {
            val id = m["id"]?.primitiveOrNull()?.contentOrNull
            val keep = id == null || keepsOwnPosition(
                id,
                m["latitude"]?.primitiveOrNull()?.doubleOrNull,
                m["longitude"]?.primitiveOrNull()?.doubleOrNull,
                m["locationAccuracyM"]?.primitiveOrNull()?.floatOrNull,
                plan.oldLatitude, plan.oldLongitude, movedIds
            )
            if (!keep) {
                val lat = plan.newLatitude
                val lon = plan.newLongitude
                if (lat != null && lon != null) {
                    m["latitude"] = JsonPrimitive(lat)
                    m["longitude"] = JsonPrimitive(lon)
                } else {
                    m.remove("latitude")
                    m.remove("longitude")
                }
            }
        }
        JsonObject(m)
    }

    /** verifications.jsonl (oder Variante): verifiedAtMs und CLUSTER_MERGED-lastSeenAt verschieben. */
    fun eventsText(text: String, deltaMs: Long): String {
        if (deltaMs == 0L) return text
        return mapObjects(text) { o ->
            val m = LinkedHashMap(o)
            shiftLong(m, "verifiedAtMs", deltaMs)
            if (m["actionType"]?.primitiveOrNull()?.contentOrNull == VerificationEvent.ACTION_CLUSTER_MERGED) {
                m["correctedSpecies"]?.primitiveOrNull()?.takeIf { it.isString }?.content?.let { p ->
                    m["correctedSpecies"] = JsonPrimitive(shiftMergePayload(p, deltaMs))
                }
            }
            JsonObject(m)
        }
    }

    /** track.jsonl: `time` verschieben (Lokalzeit mit Offset). */
    fun trackText(text: String, deltaMs: Long, zone: ZoneId): String {
        if (deltaMs == 0L) return text
        return mapObjects(text) { o ->
            val t = o["time"]?.primitiveOrNull()?.takeIf { it.isString }?.content ?: return@mapObjects o
            JsonObject(LinkedHashMap(o).apply { put("time", JsonPrimitive(shiftIso(t, deltaMs, zone))) })
        }
    }

    private fun shiftLong(m: MutableMap<String, JsonElement>, key: String, delta: Long, onlyPositive: Boolean = false) {
        val v = m[key]?.primitiveOrNull()?.takeIf { !it.isString }?.longOrNull ?: return
        if (onlyPositive && v <= 0L) return
        m[key] = JsonPrimitive(v + delta)
    }

    private fun JsonElement.primitiveOrNull(): JsonPrimitive? = try { jsonPrimitive } catch (_: IllegalArgumentException) { null }

    /** Zeilenweise: JSON-Objekt bearbeiten; Leer- und unlesbare Zeilen sowie Zeilenenden bleiben erhalten. */
    private fun mapObjects(text: String, f: (JsonObject) -> JsonObject): String =
        text.split('\n').joinToString("\n") { line ->
            if (line.isBlank()) return@joinToString line
            val obj = try { SessionJson.lenient.parseToJsonElement(line) as? JsonObject } catch (_: Exception) { null }
                ?: return@joinToString line
            val out = f(obj)
            if (out == obj) line else SessionJson.compact.encodeToString(JsonElement.serializer(), out)
        }

    // ── Ausfuehrung ──────────────────────────────────────────────────

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun encodePlan(plan: SessionInfoEditPlan): String = SessionJson.pretty.encodeToString(plan)

    fun decodePlan(text: String): SessionInfoEditPlan? =
        try { SessionJson.lenient.decodeFromString<SessionInfoEditPlan>(text) } catch (_: Exception) { null }

    /**
     * Auftrag ausfuehren (Aufrufer haelt den fileMutex). Reihenfolge: Detektionen/Verifikationen/Varianten/Track,
     * dann session.json, T273: dann Ordner umbenennen/verschieben ([folder], die Auftragsdatei wandert mit),
     * dann Auftragsdatei loeschen. Jede Datei wird nur umgerechnet, solange ihr Inhalt noch
     * dem Stand vor der Bearbeitung entspricht (Hash) — ein Wiederholen nach Abbruch verschiebt nie doppelt.
     * Jede einzelne Datei wird atomar ersetzt ([SessionFiles.writeReplacing]).
     * T273: Ohne [folder] bleibt ein Auftrag mit Umbenennung liegen (wird beim naechsten Oeffnen nachgeholt).
     * @return Anzahl umgeschriebener Dateien (ohne session.json)
     */
    fun execute(
        files: SessionFiles,
        plan: SessionInfoEditPlan,
        sessionJsonName: String = "session.json",
        folder: SessionFolder? = null,
        onRename: (SessionRename.Outcome) -> Unit = {}
    ): Int {
        var written = 0
        for ((name, hash) in plan.originalHashes) {
            val text = files.readText(name) ?: continue
            if (sha256(text) != hash) continue  // schon umgerechnet (Abbruch nach diesem Schritt)
            val events = verificationsFor(name)?.let { files.readText(it) }
            val out = transform(name, text, plan, events)
            if (out != text) {
                files.writeReplacing(name, out)
                written++
            }
        }
        val currentJson = files.readText(sessionJsonName)
        if (plan.oldSessionJsonHash == null || currentJson == null || sha256(currentJson) == plan.oldSessionJsonHash) {
            files.writeReplacing(sessionJsonName, SessionJson.pretty.encodeToString(plan.metadata))
        }
        val rename = plan.rename
        if (rename == null) {
            files.delete(JOURNAL_FILE)
            return written
        }
        if (folder == null) return written  // T273: Ordner nicht greifbar -> Auftrag bleibt liegen
        onRename(SessionRename.apply(folder, rename))
        folder.files().delete(JOURNAL_FILE)  // Auftrag liegt jetzt im umbenannten Ordner
        return written
    }

    /** Liegengebliebenen Auftrag zu Ende fuehren; null = keiner vorhanden. Unlesbarer Auftrag wird verworfen. */
    fun resumePending(
        files: SessionFiles,
        sessionJsonName: String = "session.json",
        folder: SessionFolder? = null,
        onRename: (SessionRename.Outcome) -> Unit = {}
    ): SessionInfoEditPlan? {
        val text = files.readText(JOURNAL_FILE) ?: return null
        val plan = decodePlan(text)
        if (plan == null) {
            files.delete(JOURNAL_FILE)
            return null
        }
        execute(files, plan, sessionJsonName, folder, onRename)
        return plan
    }

    /**
     * Auftrag vorbereiten: Hashes der betroffenen Dateien und der alten session.json eintragen und (falls Dateien
     * betroffen sind) die Auftragsdatei atomar schreiben. Danach [execute].
     */
    fun prepare(files: SessionFiles, plan: SessionInfoEditPlan, sessionJsonName: String = "session.json"): SessionInfoEditPlan {
        val names = filesToEdit(files.listNames(), plan)
        val hashes = LinkedHashMap<String, String>()
        for (n in names) files.readText(n)?.let { hashes[n] = sha256(it) }
        val full = plan.copy(
            oldSessionJsonHash = files.readText(sessionJsonName)?.let { sha256(it) },
            originalHashes = hashes
        )
        if (hashes.isNotEmpty() || full.rename != null) files.writeReplacing(JOURNAL_FILE, encodePlan(full))
        return full
    }
}
