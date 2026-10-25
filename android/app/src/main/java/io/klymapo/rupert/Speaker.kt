package io.klymapo.rupert

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Voz de Rupert con el TTS del sistema (gratis, sin red).
 * Cambia de idioma en cada respuesta según el código que devuelve el cerebro.
 * Para voces más naturales: Ajustes de Android → Salida de texto a voz → Google / Samsung,
 * y descargar los paquetes de voz de cada idioma.
 */
class Speaker(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    private fun localeFor(lang: String): Locale = when (lang.lowercase().take(2)) {
        "es" -> Locale.forLanguageTag("es-MX")
        "en" -> Locale.forLanguageTag("en-GB")
        "fr" -> Locale.FRANCE
        "de" -> Locale.GERMANY
        "ja" -> Locale.JAPAN
        "it" -> Locale.ITALY
        "pt" -> Locale.forLanguageTag("pt-BR")
        else -> Locale.forLanguageTag(lang)
    }

    /** Habla y regresa cuando termina (o falla). */
    suspend fun say(text: String, lang: String) {
        if (!ready || text.isBlank()) return
        val result = tts.setLanguage(localeFor(lang))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.forLanguageTag("es-MX"))
        }
        tts.setSpeechRate(1.08f) // Rupert habla rapidito
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            cont.invokeOnCancellation { tts.stop() }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        }
    }

    fun stop() = tts.stop()
    fun shutdown() = tts.shutdown()
}
