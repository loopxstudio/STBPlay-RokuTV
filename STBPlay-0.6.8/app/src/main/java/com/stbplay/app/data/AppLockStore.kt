package com.stbplay.app.data

import android.content.Context
import android.os.Build

class AppLockStore(context: Context) {
    private val prefs = context.getSharedPreferences("stbplay_app_lock", Context.MODE_PRIVATE)
    val enabled get() = prefs.contains("verifier")
    fun observeChanges(callback: () -> Unit): android.content.SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "verifier") callback()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }
    fun removeObserver(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
    fun waitSeconds(): Long = ((prefs.getLong("until", 0) - System.currentTimeMillis()).coerceIn(0, 30_000) + 999) / 1000
    /** Run PIN work off the UI thread. Failure counts survive an app restart. */
    @Synchronized fun verify(pin: String): Boolean {
        if (!enabled || waitSeconds() > 0) return false
        val ok = PinHash.verify(pin, prefs.getString("verifier", "") ?: "")
        if (ok) prefs.edit().remove("failures").remove("until").commit()
        else {
            val failures = prefs.getInt("failures", 0) + 1
            val edit = prefs.edit().putInt("failures", failures)
            if (failures % 5 == 0) edit.putLong("until", System.currentTimeMillis() + 30_000)
            edit.commit()
        }
        return ok
    }
    @Synchronized fun setPin(pin: String): Boolean {
        val algorithm = if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        val verifier = PinHash.create(pin, algorithm)
        return prefs.edit().putString("verifier", verifier).remove("failures").remove("until").commit()
    }
    @Synchronized fun disable(): Boolean = prefs.edit().clear().commit()
}
