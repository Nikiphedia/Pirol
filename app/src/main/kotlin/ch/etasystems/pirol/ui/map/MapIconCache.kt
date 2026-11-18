package ch.etasystems.pirol.ui.map

/** T115: Schluessel fuer ein Marker-Icon (alles, was das Aussehen bestimmt). */
data class MarkerIconKey(
    val color: Int,
    val label: String,
    val locked: Boolean,
    val size: Int,
    val atlasCode: String?,
    val count: Int,
    val ringColor: Int?
)

/** T115: Kleiner LRU-Cache, damit Marker-Icons nicht bei jedem Neuaufbau neu gezeichnet werden. Nur Main-Thread. */
class LruCacheMap<K, V>(private val maxSize: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    val size: Int get() = map.size

    fun getOrPut(key: K, create: () -> V): V = map[key] ?: create().also { map[key] = it }
}
