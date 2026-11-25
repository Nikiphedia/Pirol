package ch.etasystems.pirol.data.repository

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * T273: File-Modus. [base] = PIROL-Basis, Tagesordner `base/YYYY-MM-DD/`. Umbenennen und Verschieben sind je
 * ein `File.renameTo` (gleiches Dateisystem, atomar).
 */
internal class FileSessionFolder(private val base: File, private var dir: File, override var day: String?) : SessionFolder {
    override val name: String get() = dir.name
    override val key: File get() = dir

    private fun parent(day: String?): File = if (day == null) base else File(base, day)

    override fun exists(name: String, day: String?): Boolean = File(parent(day), name).exists()

    override fun rename(newName: String) {
        val target = File(dir.parentFile, newName)
        if (!dir.renameTo(target)) throw IllegalStateException("Umbenennen fehlgeschlagen: ${dir.name} -> $newName")
        dir = target
    }

    override fun moveTo(day: String?) {
        val parent = parent(day).also { it.mkdirs() }
        val target = File(parent, dir.name)
        if (!dir.renameTo(target)) throw IllegalStateException("Verschieben fehlgeschlagen: ${dir.name} -> ${day ?: "Basis"}")
        dir = target
        this.day = day
    }

    override fun files(): SessionFiles = FileSessionFiles(dir)

    companion object {
        /** Ordner einer File-Session: Tagesordner aus dem Elternordner (YYYY-MM-DD), sonst Basis = Elternordner. */
        fun of(sessionDir: File): FileSessionFolder? {
            val parent = sessionDir.parentFile ?: return null
            return if (SessionLocator.looksLikeDateDir(parent.name)) {
                FileSessionFolder(parent.parentFile ?: return null, sessionDir, parent.name)
            } else FileSessionFolder(parent, sessionDir, null)
        }
    }
}

/**
 * T273: SAF-Modus. Umbenennen ueber DocumentsContract (renameTo), Verschieben ueber moveDocument (Provider
 * muss es koennen; sonst Ausnahme -> Session bleibt gueltig im alten Tagesordner, siehe [SessionRename.apply]).
 */
internal class DocSessionFolder(
    private val context: Context,
    private val base: DocumentFile,
    doc: DocumentFile,
    override var day: String?
) : SessionFolder {
    var doc: DocumentFile = doc
        private set

    override val name: String get() = doc.name ?: ""
    override val key: File get() = SessionFilesLogic.safKey(day, name)

    private fun parent(day: String?, create: Boolean): DocumentFile? = when {
        day == null -> base
        create -> base.findOrCreateDir(day)
        else -> base.findFile(day)
    }

    override fun exists(name: String, day: String?): Boolean = parent(day, create = false)?.findFile(name) != null

    override fun rename(newName: String) {
        val oldName = name
        if (!doc.renameTo(newName)) throw IllegalStateException("SAF: Umbenennen fehlgeschlagen -> $newName")
        if (doc.name != newName) {
            // T303: Provider hat den Namen abgeaendert -> zurueck auf den alten Namen (sonst gilt der tatsaechliche)
            runCatching { doc.renameTo(oldName) }
            throw IllegalStateException("SAF: Name vom Provider abgeaendert (${doc.name}) -> $newName")
        }
    }

    override fun moveTo(day: String?) {
        val src = parent(this.day, create = false) ?: throw IllegalStateException("SAF: Quellordner fehlt")
        val dst = parent(day, create = true) ?: throw IllegalStateException("SAF: Tagesordner $day nicht erstellbar")
        val n = name
        DocumentsContract.moveDocument(context.contentResolver, doc.uri, src.uri, dst.uri)
            ?: throw IllegalStateException("SAF: Verschieben nicht moeglich")
        doc = dst.findFile(n) ?: throw IllegalStateException("SAF: verschobener Ordner nicht gefunden")
        this.day = day
    }

    override fun files(): SessionFiles = DocSessionFiles(context, doc)

    companion object {
        /** Tagesordner aus dem synthetischen Schluessel /saf/[Tag/]{name} */
        fun dayOfKey(key: File): String? =
            key.parentFile?.takeIf { it.path != File(SessionFilesLogic.SAF_KEY_ROOT).path }?.name
    }
}
