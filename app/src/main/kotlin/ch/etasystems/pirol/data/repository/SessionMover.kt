package ch.etasystems.pirol.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * T180: Kopiert einen Session-Ordner (rekursiv, inkl. memos/) in einen frei gewaehlten SAF-Zielordner.
 * Quelle ist ein DocumentFile (SAF-Session) oder ein File-Pfad (dann via DocumentFile.fromFile).
 * Das Loeschen des Originals macht der Aufrufer erst nach erfolgreicher, groessengeprueefter Kopie.
 */
class SessionMover(private val context: Context) {

    /** Anzahl Dateien und Bytes einer Quelle (fuer Fortschritt). */
    fun measure(src: DocumentFile): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        for (c in src.listFiles()) {
            if (c.isDirectory) {
                val (f, b) = measure(c); files += f; bytes += b
            } else { files++; bytes += c.length() }
        }
        return files to bytes
    }

    fun sourceFor(dir: File, doc: DocumentFile?): DocumentFile? =
        doc ?: if (dir.exists()) DocumentFile.fromFile(dir) else null

    fun targetRoot(treeUri: Uri): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.isDirectory && it.canWrite() }

    /**
     * Kopiert [src] als Unterordner von [targetRoot]. Liefert den Zielordner.
     * Bei Fehler oder Abbruch wird die Teilkopie entfernt und die Ausnahme weitergereicht.
     * [onBytes] wird mit der Anzahl neu kopierter Bytes gerufen, [onFile] nach jeder Datei.
     */
    suspend fun copySession(
        src: DocumentFile,
        targetRoot: DocumentFile,
        onBytes: (Long) -> Unit,
        onFile: () -> Unit
    ): DocumentFile {
        val existing = targetRoot.listFiles().mapNotNull { it.name }
        val name = SessionBatchLogic.uniqueName(src.name ?: "session", existing)
        val dest = targetRoot.createDirectory(name) ?: throw IOException("Zielordner nicht erstellbar: $name")
        try {
            copyDir(src, dest, onBytes, onFile)
            return dest
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                try { dest.delete() } catch (x: Exception) { Log.w(TAG, "Aufraeumen fehlgeschlagen", x) }
            }
            throw e
        }
    }

    private suspend fun copyDir(src: DocumentFile, dest: DocumentFile, onBytes: (Long) -> Unit, onFile: () -> Unit) {
        for (child in src.listFiles()) {
            currentCoroutineContext().ensureActive()
            val childName = child.name ?: continue
            if (child.isDirectory) {
                val sub = dest.createDirectory(childName) ?: throw IOException("Ordner nicht erstellbar: $childName")
                copyDir(child, sub, onBytes, onFile)
            } else {
                val out = dest.createFile("application/octet-stream", childName)
                    ?: throw IOException("Datei nicht erstellbar: $childName")
                // Generischer MIME-Typ: verhindert, dass der Provider eine Endung an den Namen haengt
                val expected = child.length()
                val resolver = context.contentResolver
                val input = resolver.openInputStream(child.uri) ?: throw IOException("Quelle nicht lesbar: $childName")
                val output = resolver.openOutputStream(out.uri, "wt") ?: run {
                    input.close(); throw IOException("Ziel nicht schreibbar: $childName")
                }
                input.use { i ->
                    output.use { o ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = i.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            onBytes(n.toLong())
                            currentCoroutineContext().ensureActive()
                        }
                        o.flush()
                    }
                }
                val actual = out.length()
                if (actual != expected) throw IOException("Groessenpruefung fehlgeschlagen: $childName ($actual != $expected)")
                onFile()
            }
        }
    }

    private companion object { const val TAG = "SessionMover" }
}
