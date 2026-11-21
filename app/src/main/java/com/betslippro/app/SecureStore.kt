package com.betslippro.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API keys live in AES-256 encrypted preferences (Android Keystore), never in plain text or backups. */
object SecureStore {
    private val SECRET_KEYS = listOf("api_token", "odds_api_key")

    fun get(context: Context): SharedPreferences = try {
        val key = MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context.applicationContext, "bet_slip_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Some devices have a broken Keystore; fall back to private (non-backed-up) storage rather than crash.
        context.applicationContext.getSharedPreferences("bet_slip_secure_fallback", Context.MODE_PRIVATE)
    }

    /** Moves keys saved by older versions out of the plain preferences file. */
    fun migrate(plain: SharedPreferences, secure: SharedPreferences) {
        val edit = secure.edit(); val clean = plain.edit(); var any = false
        SECRET_KEYS.forEach { k ->
            val v = plain.getString(k, null)
            if (!v.isNullOrBlank() && secure.getString(k, null).isNullOrBlank()) { edit.putString(k, v); any = true }
            if (plain.contains(k)) { clean.remove(k); any = true }
        }
        if (any) { edit.apply(); clean.apply() }
    }
}
