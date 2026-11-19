package ch.etasystems.pirol.data.sync

import android.content.SharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCredentialStoreTest {

    /** Fake-Cipher: XOR plus fester IV, nur fuer Tests. */
    private class FakeCipher(var fail: Boolean = false) : SecretCipher {
        private val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        override fun encrypt(plain: ByteArray): ByteArray {
            if (fail) throw IllegalStateException("Keystore nicht verfuegbar")
            return SecretCodec.pack(iv, ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() })
        }
        override fun decrypt(packed: ByteArray): ByteArray {
            if (fail) throw IllegalStateException("Keystore nicht verfuegbar")
            val (_, ct) = SecretCodec.unpack(packed)
            return ByteArray(ct.size) { (ct[it].toInt() xor 0x5A).toByte() }
        }
    }

    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, String>()
        override fun getString(key: String, defValue: String?) = map[key] ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val pending = HashMap<String, String?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun commit(): Boolean {
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }
            override fun apply() { commit() }
            override fun putStringSet(key: String, values: MutableSet<String>?) = this
            override fun putInt(key: String, value: Int) = this
            override fun putLong(key: String, value: Long) = this
            override fun putFloat(key: String, value: Float) = this
            override fun putBoolean(key: String, value: Boolean) = this
            override fun clear() = this
        }
        override fun getAll(): MutableMap<String, *> = map
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    }

    @Test
    fun packUnpackRoundtrip() {
        val iv = ByteArray(12) { it.toByte() }
        val ct = ByteArray(20) { (it * 3).toByte() }
        val (iv2, ct2) = SecretCodec.unpack(SecretCodec.pack(iv, ct))
        assertArrayEquals(iv, iv2)
        assertArrayEquals(ct, ct2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unpackRejectsTruncated() {
        SecretCodec.unpack(byteArrayOf(12, 1, 2))
    }

    @Test
    fun base64Roundtrip() {
        val data = ByteArray(256) { it.toByte() }
        assertArrayEquals(data, SecretCodec.fromBase64(SecretCodec.toBase64(data)))
    }

    @Test
    fun storeRoundtripAndNoPlaintext() {
        val prefs = FakePrefs()
        val store = UploadCredentialStore({ prefs }, FakeCipher())
        assertFalse(store.hasSmbPassword())
        assertTrue(store.setSmbPassword("geheim-123"))
        assertEquals("geheim-123", store.getSmbPassword())
        assertTrue(store.hasSmbPassword())
        assertFalse(prefs.map.values.any { it.contains("geheim-123") })
        store.clearSmbPassword()
        assertNull(store.getSmbPassword())
    }

    @Test
    fun cipherFailureNoPlainFallback() {
        val prefs = FakePrefs()
        val store = UploadCredentialStore({ prefs }, FakeCipher(fail = true))
        assertFalse(store.setSmbPassword("x"))
        assertTrue(prefs.map.isEmpty())
        assertNull(store.getSmbPassword())
    }

    @Test
    fun corruptValueTreatedAsUnset() {
        val prefs = FakePrefs()
        prefs.map[UploadCredentialStore.KEY_SMB_PASSWORD] = "%%%kein-base64"
        val store = UploadCredentialStore({ prefs }, FakeCipher())
        assertNull(store.getSmbPassword())
    }
}
