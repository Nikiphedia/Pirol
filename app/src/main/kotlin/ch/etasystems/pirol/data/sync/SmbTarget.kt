package ch.etasystems.pirol.data.sync

import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.data.repository.SessionNoteUtil
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Laedt die Session-ZIP auf eine SMB2/3-Freigabe (Windows-Share/NAS) via smbj.
 * Das Passwort wird nur im Speicher gehalten und nie geloggt.
 * Hinweis: Netzwerkcode, nicht per Unit-Test gegen ein echtes NAS pruefbar (siehe Handover).
 */
class SmbTarget(
    private val settings: SmbSettings,
    private val password: String
) : UploadTarget {

    override val name = "NAS (SMB)"

    private fun config(): SmbConfig = SmbConfig.builder()
        .withTimeout(30, TimeUnit.SECONDS)
        .withSoTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun <T> withShare(block: (DiskShare) -> T): T {
        val errors = UploadConfig.validateSmb(settings, password)
        if (errors.isNotEmpty()) throw UploadConfigException(errors.first())
        val (domain, user) = UploadConfig.splitUser(settings.user)
        val client = SMBClient(config())
        try {
            client.connect(settings.host.trim()).use { connection ->
                val session = connection.authenticate(
                    AuthenticationContext(user, password.toCharArray(), domain)
                )
                val share = session.connectShare(settings.share.trim()) as? DiskShare
                    ?: throw UploadConfigException("Die Freigabe ist keine Dateifreigabe")
                return share.use { block(it) }
            }
        } finally {
            client.close()
        }
    }

    private fun ensureFolder(share: DiskShare, folder: String) {
        var current = ""
        for (seg in folder.split('/').filter { it.isNotEmpty() }) {
            current = if (current.isEmpty()) seg else "$current/$seg"
            if (!share.folderExists(current)) share.mkdir(current)
        }
    }

    /** Verbindung, Anmeldung, Share und Zielordner pruefen (legt den Ordner bei Bedarf an). */
    fun testConnection() {
        val folder = UploadConfig.normalizeFolderPath(settings.path)
            ?: throw UploadConfigException("Pfad enthaelt ungueltige Zeichen")
        withShare { share -> ensureFolder(share, folder) }
    }

    override suspend fun upload(
        zipFile: File,
        metadata: SessionMetadata,
        onProgress: (Float) -> Unit
    ): Boolean {
        val remote = UploadConfig.buildRemotePath(
            settings.path, SessionNoteUtil.zipFileName(metadata.sessionId, metadata.note)
        )
        val folder = remote.substringBeforeLast('/', "")
        val total = zipFile.length().coerceAtLeast(1L)
        withShare { share ->
            ensureFolder(share, folder)
            share.openFile(
                remote,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            ).use { remoteFile ->
                var written = 0L
                remoteFile.outputStream.use { os ->
                    zipFile.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            written += n
                            onProgress(written.toFloat() / total)
                        }
                    }
                }
            }
        }
        onProgress(1f)
        return true
    }
}
