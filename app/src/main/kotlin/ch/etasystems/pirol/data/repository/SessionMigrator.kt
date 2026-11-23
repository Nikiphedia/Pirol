package ch.etasystems.pirol.data.repository

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * T251 (N7): Migration der Sessions an einen anderen Speicherort (T41, T51, T141), aus SessionManager
 * herausgeloest. Schattenkopien (T240) werden nie mitkopiert; die Zusatzdatei der Detektions-Updates
 * (T251) schon (sie wird am Ziel beim naechsten Oeffnen zusammengefuehrt).
 */
internal class SessionMigrator(
    private val context: Context,
    private val listSessions: () -> List<File>,
    private val docFor: (File) -> DocumentFile?,
    private val accessFor: (File) -> SessionFiles
) {
    /** Eine Datei einer Quell-Session (relativer Pfad, Laenge, Stream-Oeffner). */
    private class SrcEntry(val rel: String, val length: Long, val open: () -> InputStream)

    private fun collectEntries(sessionDir: File): List<SrcEntry> {
        val doc = docFor(sessionDir)
        val out = mutableListOf<SrcEntry>()
        if (doc != null) {
            val cr = context.contentResolver
            fun walk(dir: DocumentFile, prefix: String) {
                for (child in dir.listFiles()) {
                    val n = child.name ?: continue
                    if (!child.isDirectory && ShadowReplace.isShadowFile(n)) continue  // T240
                    if (child.isDirectory) walk(child, "$prefix$n/")
                    else out += SrcEntry("$prefix$n", child.length()) {
                        cr.openInputStream(child.uri) ?: throw IOException("Nicht lesbar: $n")
                    }
                }
            }
            walk(doc, "")
        } else {
            sessionDir.walkTopDown().filter { it.isFile && !ShadowReplace.isShadowFile(it.name) }.forEach { f ->
                out += SrcEntry(f.relativeTo(sessionDir).path.replace('\\', '/'), f.length()) { f.inputStream() }
            }
        }
        return out
    }

    /** Sessions zaehlen und Gesamtgroesse berechnen (blockierend, IO-Dispatcher). */
    fun info(): MigrationInfo {
        val sessions = listSessions()
        return MigrationInfo(sessions.size, sessions.sumOf { dir -> collectEntries(dir).sumOf { it.length } })
    }

    /** Ziel-Schreiber einer Migration (File oder SAF). */
    private interface Sink {
        /** Legt den Session-Ordner an; false = Session existiert am Ziel schon. */
        fun begin(sessionName: String, dayDir: String?): Boolean
        /** Schreibt eine Datei und verifiziert die Groesse; liefert geschriebene Bytes. */
        fun write(entry: SrcEntry): Long
        /** Verwirft die angefangene Session am Ziel. */
        fun rollback()
    }

    private class FileSink(private val base: File) : Sink {
        private var dir: File? = null
        override fun begin(sessionName: String, dayDir: String?): Boolean {
            val parent = if (dayDir != null) File(base, dayDir) else base
            val d = File(parent, sessionName)
            if (d.exists()) return false
            if (!d.mkdirs()) throw IOException("Verzeichnis nicht erstellbar")
            dir = d
            return true
        }
        override fun write(entry: SrcEntry): Long {
            val f = File(dir!!, entry.rel)
            f.parentFile?.mkdirs()
            val n = entry.open().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (f.length() != n || (entry.length > 0 && n != entry.length))
                throw IOException("${entry.rel}: Groessenabweichung (${f.length()} != ${entry.length})")
            return n
        }
        override fun rollback() { dir?.deleteRecursively() }
    }

    private inner class TreeSink(private val root: DocumentFile) : Sink {
        private var dir: DocumentFile? = null
        private val cr = context.contentResolver
        override fun begin(sessionName: String, dayDir: String?): Boolean {
            val parent = if (dayDir != null) root.findOrCreateDir(dayDir)
                ?: throw IOException("Tagesordner nicht erstellbar") else root
            if (parent.findFile(sessionName)?.isDirectory == true) return false
            dir = parent.createDirectory(sessionName) ?: throw IOException("Verzeichnis nicht erstellbar")
            return true
        }
        override fun write(entry: SrcEntry): Long {
            var d = dir!!
            val parts = entry.rel.split('/')
            for (p in parts.dropLast(1)) d = d.findOrCreateDir(p) ?: throw IOException("$p: Ordner nicht erstellbar")
            val fileName = parts.last()
            val mime = when {
                fileName.endsWith(".json") -> "application/json"
                fileName.endsWith(".wav") -> "audio/wav"
                fileName.endsWith(".txt") -> "text/plain"
                else -> "application/octet-stream"
            }
            val dest = d.createFile(mime, fileName) ?: throw IOException("$fileName: Datei nicht erstellbar")
            val n = entry.open().use { i ->
                (cr.openOutputStream(dest.uri) ?: throw IOException("$fileName: nicht beschreibbar"))
                    .use { o -> i.copyTo(o) }
            }
            val destSize = cr.openFileDescriptor(dest.uri, "r")?.use { it.statSize } ?: dest.length()
            if (destSize != n || (entry.length > 0 && n != entry.length))
                throw IOException("$fileName: Groessenabweichung ($destSize != ${entry.length})")
            return n
        }
        override fun rollback() { dir?.delete() }
    }

    /**
     * T141: Kopiert alle Sessions an das Ziel, mit Groessenpruefung pro Datei. Session am Ziel vorhanden:
     * uebersprungen. Fehler: Zielordner dieser Session verworfen, Quelle unveraendert. deleteSource: Originale
     * erst am Ende und nur ohne fehlgeschlagene Session loeschen. Aufrufer prueft "keine Aufnahme aktiv".
     */
    suspend fun migrate(
        target: MigrationTarget,
        deleteSource: Boolean,
        onProgress: (MigrationProgress) -> Unit
    ): MigrationResult {
        val sink: Sink = when (target) {
            is MigrationTarget.Dir -> { target.base.mkdirs(); FileSink(target.base) }
            is MigrationTarget.Tree -> {
                val root = DocumentFile.fromTreeUri(context, target.uri)
                    ?: return MigrationResult(0, 0L, listOf("Zielordner nicht erreichbar"), 0, 0)
                TreeSink(root)
            }
        }
        val sessionDirs = listSessions()
        var copied = 0
        var skipped = 0
        var bytesCopied = 0L
        val errors = mutableListOf<String>()
        val copiedSources = mutableListOf<File>()
        onProgress(MigrationProgress(0, sessionDirs.size, 0L))

        sessionDirs.forEachIndexed { index, sessionDir ->
            try {
                if (!sink.begin(sessionDir.name, SessionMigrationLogic.dayDirOf(sessionDir))) {
                    skipped++
                } else {
                    try {
                        var bytes = 0L
                        for (e in collectEntries(sessionDir)) {
                            currentCoroutineContext().ensureActive()
                            bytes += sink.write(e)
                        }
                        bytesCopied += bytes
                        copied++
                        copiedSources += sessionDir
                    } catch (e: Exception) {
                        sink.rollback()
                        throw e
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.add("${sessionDir.name}: ${e.message}")
                Log.e(TAG, "Session-Migration fehlgeschlagen: ${sessionDir.name}", e)
            }
            onProgress(MigrationProgress(index + 1, sessionDirs.size, bytesCopied))
        }

        if (SessionMigrationLogic.shouldDeleteSources(deleteSource, errors.size)) {
            for (src in copiedSources) {
                try { accessFor(src).deleteAll() } catch (e: Exception) { Log.w(TAG, "Original nicht geloescht: ${src.name}", e) }
            }
        }
        return MigrationResult(copied, bytesCopied, errors, skipped, errors.size)
    }

    private companion object { const val TAG = "SessionManager" }
}
