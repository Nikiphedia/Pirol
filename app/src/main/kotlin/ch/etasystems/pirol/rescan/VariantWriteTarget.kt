package ch.etasystems.pirol.rescan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * T303 (Befund 3): Ziel einer Nutzeraktion (Verifikation, +Art, Aenderung), die fuer eine Rescan-Variante
 * gedacht ist. Die Variante kann inzwischen ins Original uebernommen ([RescanPromotion]) oder geloescht worden
 * sein, waehrend die Analyse sie noch anzeigt. Nie neue verwaiste `*.rescan-<id>.jsonl` anlegen.
 * Reine Logik; der Aufrufer prueft unter dem fileMutex (die Uebernahme laeuft ebenfalls darunter).
 */
object VariantWriteTarget {

    sealed interface Target {
        /** In die Variante [variant] schreiben (null = Original) */
        data class Write(val variant: String?) : Target
        /** Variante geloescht (nicht uebernommen): Aktion verwerfen */
        data object Drop : Target
    }

    /**
     * @param metaExists Metadaten `rescan-<id>.json` der Variante vorhanden
     * @param promoted Variante wurde in diesem Prozess ins Original uebernommen ([PromotedVariants])
     */
    fun resolve(variant: String?, metaExists: Boolean, promoted: Boolean): Target = when {
        variant == null -> Target.Write(null)
        metaExists -> Target.Write(variant)
        promoted -> Target.Write(null)  // gleiche Detektions-IDs im Original
        else -> Target.Drop
    }
}

/**
 * T303: Varianten, die in diesem Prozess ins Original uebernommen wurden (Session-Pfad + Kennung).
 * Nur Speicher: nach einem Neustart zeigt keine Analyse mehr die alte Variante.
 */
object PromotedVariants {
    private val set = ConcurrentHashMap.newKeySet<String>()

    private fun key(sessionDir: File, id: String) = sessionDir.path + "|" + id

    fun add(sessionDir: File, id: String) { set += key(sessionDir, id) }
    fun contains(sessionDir: File, id: String): Boolean = key(sessionDir, id) in set
}

/** T303 (Befund 4): Feststellen, ob ein fertiger Scan ins Original uebernommen wurde. */
object PromotionCheck {

    /**
     * true = Variante ist jetzt das Original. Laeuft nach erledigter Arbeit [NonCancellable]: ein Abbruch in
     * der Schlussphase darf "uebernommen" nicht zu "nicht uebernommen" machen. [recorded] = in diesem Prozess
     * vermerkt ([PromotedVariants]); sonst [metaGone] (Metadaten der Variante fehlen). Fehler -> false,
     * CancellationException wird nie verschluckt.
     */
    suspend fun isPromoted(status: String, recorded: Boolean, metaGone: suspend () -> Boolean): Boolean {
        if (status != RescanStatus.DONE) return false
        if (recorded) return true
        return withContext(NonCancellable) {
            try {
                metaGone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
    }
}
