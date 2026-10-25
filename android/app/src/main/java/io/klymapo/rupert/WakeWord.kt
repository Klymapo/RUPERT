package io.klymapo.rupert

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Detector de wake word basado en la arquitectura de openWakeWord (Apache-2.0):
 *   audio 16 kHz → melspectrogram.tflite → embedding_model.tflite → modelo de la palabra.
 * Implementación propia en Kotlin; los modelos .tflite se descargan al compilar el APK.
 *
 * Se alimenta con bloques de 1280 muestras (80 ms). Devuelve la probabilidad 0..1.
 */
class WakeWord private constructor(
    private val melspec: Interpreter,
    private val embedding: Interpreter,
    private val classifier: Interpreter,
    val name: String,
) : AutoCloseable {

    companion object {
        const val CHUNK = 1280
        private const val CONTEXT = 160 * 3          // muestras extra para el melspectrograma
        private const val MEL_WINDOW = 76            // frames de mel por embedding
        private const val MEL_BINS = 32
        private const val EMB_DIM = 96
        private const val EMB_WINDOW = 16            // embeddings que ve el clasificador

        /** Carga el modelo propio ("rupert.tflite" importado) o el incluido de prueba. */
        fun load(context: Context): WakeWord? = try {
            val custom = customModelFile(context)
            val (classifierBuf, name) = if (custom.exists()) {
                mapFile(custom) to "Rupert"
            } else {
                mapAsset(context, "wakeword/hey_jarvis.tflite") to "Hey Jarvis (prueba)"
            }
            val options = Interpreter.Options().setNumThreads(2)
            WakeWord(
                melspec = Interpreter(mapAsset(context, "wakeword/melspectrogram.tflite"), options),
                embedding = Interpreter(mapAsset(context, "wakeword/embedding_model.tflite"), options),
                classifier = Interpreter(classifierBuf, options),
                name = name,
            )
        } catch (e: Exception) {
            null
        }

        fun customModelFile(context: Context) = File(context.filesDir, "wakeword/rupert.tflite")

        private fun mapAsset(context: Context, path: String): MappedByteBuffer {
            val fd = context.assets.openFd(path)
            FileInputStream(fd.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

        private fun mapFile(file: File): MappedByteBuffer =
            FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
    }

    private val audio = FloatArray(CHUNK + CONTEXT)
    private val melFrames = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()

    private val melOutShape: IntArray
    private val classifierWindow: Int

    init {
        melspec.resizeInput(0, intArrayOf(1, CHUNK + CONTEXT))
        melspec.allocateTensors()
        melOutShape = melspec.getOutputTensor(0).shape()
        // El clasificador espera [1, N, 96]; N casi siempre es 16.
        classifierWindow = classifier.getInputTensor(0).shape().getOrElse(1) { EMB_WINDOW }
        reset()
    }

    fun reset() {
        audio.fill(0f)
        melFrames.clear()
        repeat(MEL_WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        embeddings.clear()
    }

    /** chunk: 1280 muestras PCM16. */
    fun process(chunk: ShortArray): Float {
        require(chunk.size == CHUNK)
        // desplaza el búfer y agrega el bloque nuevo (valores crudos de int16, como openWakeWord)
        System.arraycopy(audio, CHUNK, audio, 0, CONTEXT)
        for (i in 0 until CHUNK) audio[CONTEXT + i] = chunk[i].toFloat()

        // 1) melspectrograma
        val melOut = allocate(melOutShape.fold(1) { a, b -> a * b })
        melspec.run(arrayOf(audio), melOut)
        melOut.rewind()
        val frames = melOut.capacity() / 4 / MEL_BINS
        repeat(frames) {
            val frame = FloatArray(MEL_BINS) { melOut.float / 10f + 2f }
            melFrames.addLast(frame)
        }
        while (melFrames.size > MEL_WINDOW * 2) melFrames.removeFirst()
        if (melFrames.size < MEL_WINDOW) return 0f

        // 2) embedding de los últimos 76 frames
        val embIn = allocate(MEL_WINDOW * MEL_BINS)
        val start = melFrames.size - MEL_WINDOW
        for (i in start until melFrames.size) for (v in melFrames[i]) embIn.putFloat(v)
        embIn.rewind()
        val embOut = allocate(EMB_DIM)
        embedding.run(embIn, embOut)
        embOut.rewind()
        embeddings.addLast(FloatArray(EMB_DIM) { embOut.float })
        while (embeddings.size > classifierWindow) embeddings.removeFirst()
        if (embeddings.size < classifierWindow) return 0f

        // 3) clasificador de la palabra
        val clsIn = allocate(classifierWindow * EMB_DIM)
        for (e in embeddings) for (v in e) clsIn.putFloat(v)
        clsIn.rewind()
        val clsOut = allocate(1)
        classifier.run(clsIn, clsOut)
        clsOut.rewind()
        return clsOut.float
    }

    private fun allocate(floats: Int): ByteBuffer =
        ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())

    override fun close() {
        melspec.close(); embedding.close(); classifier.close()
    }
}
