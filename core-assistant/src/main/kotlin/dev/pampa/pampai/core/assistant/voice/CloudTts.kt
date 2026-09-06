package dev.pampa.pampai.core.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.antigravity.fluidengine.ai.keys.AiKeyStore
import dev.antigravity.fluidengine.ai.net.AiHttp
import dev.antigravity.fluidengine.ai.net.asArray
import dev.antigravity.fluidengine.ai.net.at
import dev.antigravity.fluidengine.ai.net.get
import dev.antigravity.fluidengine.ai.net.string
import dev.antigravity.fluidengine.ai.provider.ProviderId
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Audio pronto da suonare: PCM 16 bit mono a [sampleRate]. */
class TtsAudio(val pcm: ByteArray, val sampleRate: Int)

/** Una voce cloud: torna null quando non puo' (chiave assente, lingua non coperta), lancia sugli errori. */
interface CloudTts {
  val provider: ProviderId
  suspend fun synthesize(text: String, language: String): TtsAudio?
}

/**
 * Gemini che parla: `generateContent` con `responseModalities: AUDIO` e una voce predefinita.
 * Torna PCM 24 kHz in base64. Il modello e' quello dei TTS di Gemini; se la chiave non lo ha,
 * l'errore fa ripiegare sul sistema.
 */
class GeminiTts(private val http: AiHttp, private val keys: AiKeyStore, private val voice: String = DEFAULT_VOICE, private val model: String = DEFAULT_MODEL) : CloudTts {
  override val provider: ProviderId = ProviderId.GEMINI

  override suspend fun synthesize(text: String, language: String): TtsAudio? {
    val key = keys.key(ProviderId.GEMINI) ?: return null
    val body: JsonObject = buildJsonObject {
      put("contents", buildJsonArray { add(buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) }) }) })
      put(
        "generationConfig",
        buildJsonObject {
          put("responseModalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("AUDIO")) })
          put("speechConfig", buildJsonObject { put("voiceConfig", buildJsonObject { put("prebuiltVoiceConfig", buildJsonObject { put("voiceName", voice) }) }) })
        },
      )
    }
    val response = http.postJson("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", mapOf("x-goog-api-key" to key), body)
    val part = response.body["candidates"].at(0)["content"]["parts"].asArray().firstOrNull { it["inlineData"] != null } ?: return null
    val data = part["inlineData"]["data"].string() ?: return null
    val mime = part["inlineData"]["mimeType"].string().orEmpty()
    val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24_000
    return TtsAudio(Base64.getDecoder().decode(data), rate)
  }

  companion object {
    const val DEFAULT_MODEL = "gemini-3.5-flash-tts"
    const val DEFAULT_VOICE = "Kore"
    val VOICES = listOf("Kore", "Puck", "Zephyr", "Charon", "Fenrir", "Leda", "Aoede", "Orus")
  }
}

/** Groq che parla (PlayAI): solo inglese, WAV in risposta. Per l'italiano torna null e si passa oltre. */
class GroqTts(private val keys: AiKeyStore, private val voice: String = DEFAULT_VOICE) : CloudTts {
  override val provider: ProviderId = ProviderId.GROQ

  override suspend fun synthesize(text: String, language: String): TtsAudio? {
    if (!language.startsWith("en")) return null
    val key = keys.key(ProviderId.GROQ) ?: return null
    val body = buildJsonObject {
      put("model", "playai-tts")
      put("input", text.take(MAX_CHARS))
      put("voice", voice)
      put("response_format", "wav")
    }
    val wav = RawHttp.postJsonForBytes("https://api.groq.com/openai/v1/audio/speech", mapOf("Authorization" to "Bearer $key"), body.toString())
    return WavReader.parse(wav)
  }

  companion object {
    const val DEFAULT_VOICE = "Fritz-PlayAI"
    const val MAX_CHARS = 10_000
  }
}

/** Una POST JSON che torna byte (un WAV): l'engine non ne ha una, e per una voce non vale la pena metterla li'. */
object RawHttp {
  suspend fun postJsonForBytes(url: String, headers: Map<String, String>, json: String, timeoutMillis: Int = 30_000): ByteArray = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = 10_000
      readTimeout = timeoutMillis
      doOutput = true
      instanceFollowRedirects = false
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "audio/wav, */*")
      headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
      connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      if (code !in 200..299) {
        val error = runCatching { connection.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull().orEmpty().take(300)
        throw IllegalStateException("HTTP $code $error")
      }
      connection.inputStream.use { it.readBytes() }
    } finally {
      connection.disconnect()
    }
  }
}

/** Legge un WAV PCM 16 bit: salta l'intestazione (cercando il blocco `data`) e prende la frequenza. */
object WavReader {
  fun parse(bytes: ByteArray): TtsAudio? {
    if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
    val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    var rate = 24_000
    var offset = 12
    while (offset + 8 <= bytes.size) {
      val id = String(bytes, offset, 4, Charsets.US_ASCII)
      val size = buffer.getInt(offset + 4)
      if (id == "fmt ") rate = buffer.getInt(offset + 12)
      if (id == "data") return TtsAudio(bytes.copyOfRange(offset + 8, minOf(bytes.size, offset + 8 + size)), rate)
      offset += 8 + size + (size % 2)
    }
    return null
  }
}

/** Riproduce PCM 16 bit mono con `AudioTrack`, come parlato dell'assistente (si abbassa la musica). */
class SpeechPlayer {
  @Volatile private var track: AudioTrack? = null

  suspend fun play(audio: TtsAudio) = withContext(Dispatchers.IO) {
    val minBuffer = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val player = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
      .setBufferSizeInBytes(maxOf(minBuffer, 8_192))
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
    track = player
    try {
      player.play()
      var written = 0
      while (written < audio.pcm.size && track === player) {
        val n = player.write(audio.pcm, written, minOf(8_192, audio.pcm.size - written))
        if (n <= 0) break
        written += n
      }
      // Aspetta che il buffer si svuoti prima di chiudere, altrimenti l'ultima parola sparisce.
      if (track === player) {
        val frames = audio.pcm.size / 2
        while (track === player && player.playState == AudioTrack.PLAYSTATE_PLAYING && player.playbackHeadPosition < frames) {
          Thread.sleep(40)
        }
      }
    } finally {
      runCatching { player.stop() }
      runCatching { player.release() }
      if (track === player) track = null
    }
  }

  fun stop() {
    val current = track ?: return
    track = null
    runCatching { current.pause() }
    runCatching { current.flush() }
  }
}

/** Un piccolo raccoglitore di byte, per chi accumula PCM a pezzi. */
internal class PcmSink {
  private val out = ByteArrayOutputStream()
  fun add(bytes: ByteArray) = out.write(bytes)
  fun bytes(): ByteArray = out.toByteArray()
}
