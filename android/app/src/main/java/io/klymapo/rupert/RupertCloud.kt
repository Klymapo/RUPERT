package io.klymapo.rupert

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class CloudException(message: String) : IOException(message)

data class Transcript(val text: String, val language: String)
data class Reply(val text: String, val lang: String, val mode: String)

/**
 * Cliente mínimo para el Cloud Core de Rupert en Supabase:
 * Auth con correo/contraseña, transcripción (Whisper) y cerebro (Gemini con respaldo).
 * Todas las llamadas son bloqueantes: úsalas fuera del hilo principal.
 */
class RupertCloud(private val prefs: Prefs) {

    // ---------- Auth ----------

    fun signIn(email: String, password: String) {
        val body = JSONObject().put("email", email).put("password", password)
        val res = postJson("${prefs.supabaseUrl}/auth/v1/token?grant_type=password", body, bearer = null)
        storeSession(res)
        prefs.email = email
    }

    /** Envía un código de un solo uso al correo (mismo login por OTP que usa la PWA). */
    fun sendOtp(email: String) {
        val body = JSONObject().put("email", email).put("create_user", false)
        postJson("${prefs.supabaseUrl}/auth/v1/otp", body, bearer = null)
        prefs.email = email
    }

    fun verifyOtp(email: String, code: String) {
        val body = JSONObject().put("type", "email").put("email", email).put("token", code.trim())
        storeSession(postJson("${prefs.supabaseUrl}/auth/v1/verify", body, bearer = null))
        prefs.email = email
    }

    private fun refresh() {
        if (prefs.refreshToken.isBlank()) throw CloudException("Inicia sesión en Ajustes.")
        val body = JSONObject().put("refresh_token", prefs.refreshToken)
        val res = postJson("${prefs.supabaseUrl}/auth/v1/token?grant_type=refresh_token", body, bearer = null)
        storeSession(res)
    }

    private fun storeSession(res: JSONObject) {
        val access = res.optString("access_token")
        if (access.isBlank()) throw CloudException("Supabase no devolvió sesión.")
        prefs.accessToken = access
        prefs.refreshToken = res.optString("refresh_token", prefs.refreshToken)
        prefs.expiresAt = System.currentTimeMillis() + res.optLong("expires_in", 3600) * 1000
    }

    private fun token(): String {
        if (prefs.accessToken.isBlank() || System.currentTimeMillis() > prefs.expiresAt - 60_000) refresh()
        return prefs.accessToken
    }

    // ---------- Voz y cerebro ----------

    /** Manda el WAV a la Edge Function de transcripción. language = "auto" deja que Whisper detecte. */
    fun transcribe(wav: ByteArray, language: String = "auto"): Transcript {
        val boundary = "rupert-" + UUID.randomUUID().toString()
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("language", language)
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"rupert.wav\"\r\n" +
                "Content-Type: audio/wav\r\n\r\n").toByteArray()
        )
        out.write(wav)
        out.write("\r\n--$boundary--\r\n".toByteArray())

        val res = withAuthRetry { bearer ->
            request(
                url = "${prefs.supabaseUrl}/functions/v1/rupert-transcribe",
                contentType = "multipart/form-data; boundary=$boundary",
                payload = out.toByteArray(),
                bearer = bearer,
            )
        }
        return Transcript(res.optString("text").trim(), res.optString("language", "es").ifBlank { "es" })
    }

    fun ask(message: String, langHint: String): Reply {
        val body = JSONObject()
            .put("message", message)
            .put("source", "tablet")
            .put("session_id", sessionId())
            .put("lang", langHint)
        val res = withAuthRetry { bearer ->
            postJson("${prefs.supabaseUrl}/functions/v1/rupert", body, bearer)
        }
        val reply = res.optString("reply").trim()
        if (reply.isBlank()) throw CloudException(res.optString("error", "Rupert no respondió."))
        return Reply(reply, res.optString("lang", langHint).ifBlank { langHint }, res.optString("mode"))
    }

    /** Lectura mínima para que el plan gratis de Supabase no pause el proyecto. */
    fun heartbeat() {
        request(
            url = "${prefs.supabaseUrl}/rest/v1/rupert_settings?select=user_id&limit=1",
            contentType = null,
            payload = null,
            bearer = token(),
            method = "GET",
        )
        prefs.lastHeartbeat = System.currentTimeMillis()
    }

    // ---------- HTTP ----------

    /** Una sesión por día: Rupert recuerda la plática del día sin mezclar semanas enteras. */
    private fun sessionId(): String =
        "tablet-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun withAuthRetry(call: (String) -> JSONObject): JSONObject = try {
        call(token())
    } catch (e: UnauthorizedException) {
        refresh()
        call(prefs.accessToken)
    }

    private class UnauthorizedException : IOException("unauthorized")

    private fun postJson(url: String, body: JSONObject, bearer: String?): JSONObject =
        request(url, "application/json", body.toString().toByteArray(), bearer)

    private fun request(
        url: String,
        contentType: String?,
        payload: ByteArray?,
        bearer: String?,
        method: String = "POST",
    ): JSONObject {
        if (!prefs.isConfigured) throw CloudException("Configura Supabase en Ajustes.")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("apikey", prefs.anonKey)
            conn.setRequestProperty("Authorization", "Bearer ${bearer ?: prefs.anonKey}")
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
            if (payload != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(payload) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw UnauthorizedException()
            if (code !in 200..299) {
                val detail = runCatching {
                    val j = JSONObject(text)
                    j.optString("error_description").ifBlank { j.optString("msg") }.ifBlank { j.optString("error") }
                }.getOrNull().orEmpty().ifBlank { "HTTP $code" }
                throw CloudException(detail)
            }
            return when {
                text.isBlank() -> JSONObject()
                text.trimStart().startsWith("[") -> JSONObject().put("items", org.json.JSONArray(text))
                else -> JSONObject(text)
            }
        } finally {
            conn.disconnect()
        }
    }
}
