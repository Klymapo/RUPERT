package io.klymapo.rupert

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Servicio en primer plano (tipo micrófono) que mantiene a Rupert escuchando.
 * Flujo: dormido (wake word local) → escucha → piensa (nube) → habla → escucha de seguimiento.
 * Debe arrancarse desde la app abierta: Android no permite iniciar el micrófono desde segundo plano.
 */
class RupertService : Service() {

    companion object {
        const val ACTION_START = "io.klymapo.rupert.START"
        const val ACTION_STOP = "io.klymapo.rupert.STOP"
        const val ACTION_TALK = "io.klymapo.rupert.TALK"
        const val ACTION_RELOAD = "io.klymapo.rupert.RELOAD"
        private const val CHANNEL = "rupert"
        private const val NOTIFICATION_ID = 7
        private const val RATE = 16_000
        private const val CHUNK_MS = 80
        private const val HEARTBEAT_EVERY_MS = 24L * 60 * 60 * 1000

        private val STOP_PHRASES = listOf(
            "eso es todo", "es todo", "nada más", "gracias rupert", "adiós rupert", "hasta luego",
            "that's all", "thank you rupert", "goodbye", "c'est tout", "das ist alles",
        )

        fun send(context: Context, action: String) {
            val intent = Intent(context, RupertService::class.java).setAction(action)
            if (action == ACTION_STOP) context.startService(intent)
            else ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val talkRequested = AtomicBoolean(false)
    private val reloadRequested = AtomicBoolean(false)
    private lateinit var prefs: Prefs
    private lateinit var cloud: RupertCloud
    private lateinit var speaker: Speaker
    private var wakeLock: PowerManager.WakeLock? = null
    private var noiseFloor = 200.0
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        cloud = RupertCloud(prefs)
        speaker = Speaker(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_TALK -> talkRequested.set(true)
            ACTION_RELOAD -> reloadRequested.set(true)
        }
        if (!goForeground()) return START_NOT_STICKY
        if (loopJob?.isActive != true) {
            running = true
            loopJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RupertState.mode(Mode.ERROR, "Necesito permiso de micrófono.")
            stopSelf()
            return false
        }
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            if (wakeLock == null) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rupert:listening")
                    .apply { setReferenceCounted(false); acquire() }
            }
            true
        } catch (e: Exception) {
            // Android 14+ prohíbe arrancar el micrófono desde segundo plano: hay que abrir la app.
            RupertState.mode(Mode.ERROR, "Abre la app para que Rupert pueda escuchar.")
            stopSelf()
            false
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Rupert escuchando", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RupertService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Rupert está escuchando")
            .setContentText("Di su nombre para despertarlo.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .build()
    }

    // ---------------- Bucle principal ----------------

    @SuppressLint("MissingPermission") // se revisa en goForeground()
    private suspend fun runLoop() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, WakeWord.CHUNK * 2 * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            RupertState.mode(Mode.ERROR, "No pude abrir el micrófono.")
            return
        }
        var wake = WakeWord.load(this)
        RupertState.wakeWord(wake?.name ?: "sin wake word (toca la cara)")
        val chunk = ShortArray(WakeWord.CHUNK)
        rec.startRecording()
        sleeping()
        try {
            while (running) {
                if (reloadRequested.getAndSet(false)) {
                    wake?.close()
                    wake = WakeWord.load(this)
                    RupertState.wakeWord(wake?.name ?: "sin wake word (toca la cara)")
                }
                if (!readChunk(rec, chunk)) continue
                maybeHeartbeat()

                val manual = talkRequested.getAndSet(false)
                val score = if (manual) 0f else wake?.process(chunk) ?: 0f
                if (score > 0.05f) RupertState.score(score)

                if (manual || score >= prefs.wakeThreshold) {
                    conversation(rec, chunk)
                    wake?.reset()
                    flush(rec, chunk)
                    sleeping()
                } else {
                    noiseFloor = 0.97 * noiseFloor + 0.03 * rms(chunk)
                }
            }
        } finally {
            rec.stop()
            rec.release()
            wake?.close()
        }
    }

    private fun sleeping() = RupertState.mode(Mode.SLEEPING, "")

    private suspend fun conversation(rec: AudioRecord, chunk: ShortArray) {
        if (!prefs.isConfigured || !prefs.isSignedIn) {
            RupertState.mode(Mode.ERROR, "Primero configura Supabase e inicia sesión en Ajustes.")
            delay(3000)
            return
        }
        var followUp = false
        while (running) {
            RupertState.mode(Mode.LISTENING, if (followUp) "Te escucho…" else "¿Sí?")
            val pcm = recordUtterance(rec, chunk, startTimeoutMs = if (followUp) 7000 else 6000) ?: return
            RupertState.mode(Mode.THINKING, "…")
            try {
                val heard = runInterruptible { cloud.transcribe(wav(pcm), "auto") }
                if (heard.text.isBlank()) return
                RupertState.heard(heard.text)
                prefs.lastLang = heard.language
                if (STOP_PHRASES.any { heard.text.lowercase().contains(it) }) {
                    RupertState.mode(Mode.SPEAKING, if (heard.language == "es") "¡Hasta luego!" else "Bye!")
                    speaker.say(if (heard.language == "es") "¡Hasta luego!" else "Bye!", heard.language)
                    return
                }
                val reply = runInterruptible { cloud.ask(heard.text, heard.language) }
                prefs.lastLang = reply.lang
                val excited = reply.text.count { it == '!' } >= 2 || reply.text.startsWith("¡")
                RupertState.mode(if (excited) Mode.EXCITED else Mode.SPEAKING, reply.text)
                speaker.say(reply.text, reply.lang)
            } catch (e: Exception) {
                RupertState.mode(Mode.ERROR, e.message ?: "Algo falló en la nube.")
                delay(3500)
                return
            }
            flush(rec, chunk)
            followUp = true
        }
    }

    /** Graba hasta detectar ~0.9 s de silencio después de que empiezas a hablar. */
    private fun recordUtterance(rec: AudioRecord, chunk: ShortArray, startTimeoutMs: Int): ShortArray? {
        val out = ArrayList<Short>(RATE * 6)
        val preroll = ArrayDeque<ShortArray>()
        var started = false
        var silenceMs = 0
        var elapsed = 0
        while (running) {
            if (!readChunk(rec, chunk)) continue
            elapsed += CHUNK_MS
            val loud = rms(chunk) > max(noiseFloor * 3.0, 450.0)
            if (!started) {
                preroll.addLast(chunk.copyOf())
                if (preroll.size > 4) preroll.removeFirst()
                if (loud) {
                    started = true
                    preroll.forEach { block -> block.forEach(out::add) }
                } else if (elapsed > startTimeoutMs) {
                    return null
                }
            } else {
                chunk.forEach(out::add)
                silenceMs = if (loud) 0 else silenceMs + CHUNK_MS
                if (silenceMs >= 900 || out.size > RATE * 15) break
            }
        }
        return if (out.size < RATE / 3) null else out.toShortArray()
    }

    private fun readChunk(rec: AudioRecord, chunk: ShortArray): Boolean {
        var offset = 0
        while (offset < chunk.size) {
            val n = rec.read(chunk, offset, chunk.size - offset)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    /** Descarta el audio que se acumuló mientras Rupert hablaba (para que no se escuche a sí mismo). */
    private fun flush(rec: AudioRecord, chunk: ShortArray) {
        repeat(6) { readChunk(rec, chunk) }
    }

    private fun rms(chunk: ShortArray): Double {
        var sum = 0.0
        for (s in chunk) sum += s.toDouble() * s
        return sqrt(sum / chunk.size)
    }

    private fun wav(pcm: ShortArray): ByteArray {
        val dataLen = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataLen)
        }
        val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { body.putShort(it) }
        return ByteArrayOutputStream(44 + dataLen).apply {
            write(header.array()); write(body.array())
        }.toByteArray()
    }

    private fun maybeHeartbeat() {
        if (!prefs.isSignedIn) return
        if (System.currentTimeMillis() - prefs.lastHeartbeat < HEARTBEAT_EVERY_MS) return
        prefs.lastHeartbeat = System.currentTimeMillis() // evita repetir mientras corre
        scope.launch { runCatching { cloud.heartbeat() } }
    }

    private fun stopEverything() {
        running = false
        loopJob?.cancel()
        speaker.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        RupertState.mode(Mode.OFF, "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        speaker.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (RupertState.ui.value.mode != Mode.ERROR) RupertState.mode(Mode.OFF, "")
        super.onDestroy()
    }
}
