package io.klymapo.rupert

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuración local de la tablet. Solo guarda la URL y la llave publishable/anon
 * de Supabase y la sesión del usuario; nunca llaves de servidor.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("rupert_tablet", Context.MODE_PRIVATE)

    var supabaseUrl: String
        get() = sp.getString("supabase_url", "") ?: ""
        set(v) = sp.edit().putString("supabase_url", v.trim().trimEnd('/')).apply()

    var anonKey: String
        get() = sp.getString("anon_key", "") ?: ""
        set(v) = sp.edit().putString("anon_key", v.trim()).apply()

    var email: String
        get() = sp.getString("email", "") ?: ""
        set(v) = sp.edit().putString("email", v.trim()).apply()

    var accessToken: String
        get() = sp.getString("access_token", "") ?: ""
        set(v) = sp.edit().putString("access_token", v).apply()

    var refreshToken: String
        get() = sp.getString("refresh_token", "") ?: ""
        set(v) = sp.edit().putString("refresh_token", v).apply()

    /** Momento (ms) en que vence el access token. */
    var expiresAt: Long
        get() = sp.getLong("expires_at", 0L)
        set(v) = sp.edit().putLong("expires_at", v).apply()

    /** Sensibilidad del wake word: más bajo = despierta más fácil (y con más falsos positivos). */
    var wakeThreshold: Float
        get() = sp.getFloat("wake_threshold", 0.5f)
        set(v) = sp.edit().putFloat("wake_threshold", v.coerceIn(0.1f, 0.95f)).apply()

    /** Último idioma en que habló Mario; Rupert sigue en él. */
    var lastLang: String
        get() = sp.getString("last_lang", "es") ?: "es"
        set(v) = sp.edit().putString("last_lang", v).apply()

    var lastHeartbeat: Long
        get() = sp.getLong("last_heartbeat", 0L)
        set(v) = sp.edit().putLong("last_heartbeat", v).apply()

    val isConfigured: Boolean get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank()
    val isSignedIn: Boolean get() = refreshToken.isNotBlank()

    fun signOut() = sp.edit()
        .remove("access_token").remove("refresh_token").remove("expires_at").apply()
}
