package com.glyphrank.dota.data

import android.content.SharedPreferences

/** In-memory [SharedPreferences] for JVM tests; listeners are called synchronously, like on the main thread. */
class FakeSharedPreferences : SharedPreferences {
    val values = LinkedHashMap<String, Any?>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += l
    }

    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= l
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            val changed = LinkedHashSet<String>()
            if (clear) changed += values.keys.also { values.clear() }
            for (key in removals) if (values.remove(key) != null || key in changes) changed += key
            for ((key, value) in changes) {
                if (value == null) values.remove(key) else values[key] = value
                changed += key
            }
            for (key in changed) listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakeSharedPreferences, key) }
        }
    }
}
