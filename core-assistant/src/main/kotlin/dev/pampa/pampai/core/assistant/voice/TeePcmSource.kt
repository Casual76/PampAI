package dev.pampa.pampai.core.assistant.voice

import android.os.ParcelFileDescriptor
import dev.antigravity.fluidengine.ai.speech.MicrophoneFailure
import dev.antigravity.fluidengine.ai.speech.PcmSource
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Una sorgente PCM che passa i campioni a chi la legge (il rilevatore del parlato dell'engine, che
 * ne fa il WAV per Whisper) e, in copia, a un tubo: dall'altra parte c'e' il riconoscitore di
 * sistema, che cosi' vede lo stesso audio senza aprire un secondo microfono (Android ne concede
 * uno solo per volta). La copia passa da una coda e da un thread suo: il thread audio non blocca
 * mai, e se il riconoscitore e' lento i frame piu' vecchi cadono, non l'ascolto.
 */
class TeePcmSource(
  private val inner: PcmSource,
  private val sink: ParcelFileDescriptor?,
) : PcmSource {

  private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_FRAMES)
  private val running = AtomicBoolean(false)
  private var writer: Thread? = null

  override val failure: MicrophoneFailure get() = inner.failure

  override fun start(sampleRate: Int): Boolean {
    if (!inner.start(sampleRate)) return false
    val fd = sink ?: return true
    running.set(true)
    writer = Thread({
      val out: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(fd)
      try {
        while (running.get() || queue.isNotEmpty()) {
          val chunk = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
          out.write(chunk)
        }
        out.flush()
      } catch (_: Exception) {
        // Il riconoscitore ha chiuso il tubo: si smette di copiare, il microfono continua.
      } finally {
        runCatching { out.close() }
      }
    }, "pampai-stt-tee").apply { isDaemon = true; start() }
    return true
  }

  override fun read(frame: ShortArray): Int {
    val n = inner.read(frame)
    if (n > 0 && sink != null && running.get()) {
      val bytes = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
      for (i in 0 until n) bytes.putShort(frame[i])
      if (!queue.offer(bytes.array())) {
        queue.poll()
        queue.offer(bytes.array())
      }
    }
    return n
  }

  override fun stop() {
    running.set(false)
    inner.stop()
    writer?.let { runCatching { it.join(500) } }
    writer = null
  }

  private companion object {
    /** Un secondo e passa di audio a frame da 20 ms: abbastanza per un riconoscitore che tentenna. */
    const val QUEUE_FRAMES = 64
  }
}
