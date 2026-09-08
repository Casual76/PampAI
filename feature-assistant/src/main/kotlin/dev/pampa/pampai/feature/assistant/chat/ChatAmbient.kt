package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassQuality
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.pampai.feature.assistant.halo.AuroraSpec
import dev.pampa.pampai.feature.assistant.halo.HaloColours
import dev.pampa.pampai.feature.assistant.halo.HaloMood
import dev.pampa.pampai.feature.assistant.halo.drawAuroraField
import dev.pampa.pampai.feature.assistant.halo.rememberHaloClock

// L'aurora della chat: mentre Aria ascolta, lavora o scrive, tutto lo sfondo della pagina si muove
// di colori larghi e lenti, dietro il testo e dentro il vetro della barra e del composer. A riposo
// e a risposta finita non c'e', e con il moto ridotto non parte mai.
//
// Vive nel fondale ([ChatBackdrops.canvas]), non nel corpo: la lista e' registrata a parte, cosi'
// un fondale che cambia sessanta volte al secondo non fa mai ri-registrare il testo. Il pittore e
// l'orologio sono quelli di `halo/HaloField.kt`; qui c'e' solo la regia: quando accendere, quanto
// forte, e quanto far pagare ai vetri.

/**
 * Quanto puo' costare l'aurora: piena, in economia (meno macchie, un fotogramma si' e uno no), o
 * spenta. [Off] non lo produce nessuna misura automatica: e' il valore per chi, un giorno, vorra'
 * un interruttore nelle impostazioni, e passa da qui invece che da un `if` nella rotta.
 */
enum class ChatAmbientEconomy { Full, Economy, Off }

/**
 * Ogni quanto i vetri della chat ricampionano il fondale mentre l'aurora si muove (0 = ogni
 * fotogramma). Sotto la sfocatura di una barra una cattura vecchia di cento millisecondi e' uguale
 * a una fresca, e due pannelli che rifanno la cattura a ogni fotogramma sopra un canvas animato
 * erano la differenza fra una risposta che scorre e una che scatta.
 */
internal const val ChatGlassResampleMillis = 100L

/**
 * Sotto questo livello di qualita' del vetro (la lista sta correndo, o una rotta sta passando)
 * l'aurora va in economia: e' lo stesso momento in cui il materiale rinuncia alla lente, e per la
 * stessa ragione.
 */
private const val EconomyQualityThreshold = 0.6f

/** Sotto questa presenza l'aurora non c'e': ne' disegnata, ne' motivo per tenere i vetri svegli. */
private const val AuroraPresenceFloor = 0.002f

private const val AuroraFadeInMs = 400
private const val AuroraFadeOutMs = 600

/** L'ampiezza al lavoro e in scrittura: con il picco di [AuroraSpec] fa 0.12 in chiaro e 0.18 in scuro. */
private const val WorkingAmplitude = 0.6f

/** In ascolto l'ampiezza parte da qui e sale con la voce fino a 0.75: fra due sillabe resta qualcosa. */
private const val ListeningFloor = 0.25f
private const val ListeningRange = 0.5f

/**
 * Lo stato dell'aurora: una presenza da 0 a 1 che sfuma in entrata e in uscita, e [present], la
 * risposta secca a "c'e' qualcosa da disegnare?".
 *
 * [present] e' un `derivedStateOf`, non una lettura di [presence]: chi lo legge in composizione o
 * dentro un `frozen` di `glassBackdropSource` viene invalidato due volte per risposta (accesa,
 * spenta), non sessanta volte al secondo durante la dissolvenza.
 */
@Stable
class ChatAuroraState internal constructor() {
  internal val presence = Animatable(0f)

  /** Vero mentre l'aurora ha una presenza visibile, dissolvenze comprese. */
  val present: Boolean by derivedStateOf { presence.value > AuroraPresenceFloor }
}

/**
 * Se l'umore accende l'aurora: mentre Aria ascolta, lavora o scrive. A riposo, a risposta finita e
 * nell'errore no: e' il segnale di "sta succedendo qualcosa", e a `Done` la cosa e' successa.
 */
private fun HaloMood.lightsAurora(): Boolean = this == HaloMood.LISTENING || this == HaloMood.WORKING || this == HaloMood.WRITING

/**
 * L'economia dell'aurora letta dalla qualita' del vetro in scope: [ChatAmbientEconomy.Economy]
 * mentre la pagina corre (il livello scende sotto [EconomyQualityThreshold]), piena altrimenti.
 * Senza una qualita' in scope l'aurora e' piena: nessuno ha detto che costa.
 *
 * Il livello cambia a ogni fotogramma di uno scorrimento; passa da un `derivedStateOf` cosi' chi
 * ci chiama ricompone solo quando la soglia viene attraversata, non a ogni pixel.
 */
@Composable
fun rememberChatAmbientEconomy(): ChatAmbientEconomy {
  val quality = LocalFluidGlassQuality.current
  val economy by remember(quality) {
    derivedStateOf {
      val level = quality?.level ?: 1f
      if (level < EconomyQualityThreshold) ChatAmbientEconomy.Economy else ChatAmbientEconomy.Full
    }
  }
  return economy
}

/**
 * Lo stato dell'aurora per l'umore corrente: si accende (400 ms) quando l'umore la chiede, si
 * spegne (600 ms) quando non piu'. Non parte mai con il moto ridotto — e' decorazione, e sullo
 * sfondo di un testo da leggere una decorazione ferma e' solo una macchia — ne' con l'economia a
 * [ChatAmbientEconomy.Off].
 *
 * Restituisce lo stato e non un booleano perche' la rotta ne usa due cose diverse: [ChatAuroraState.present]
 * per congelare il fondale e alzare l'intervallo dei vetri, e la presenza continua per disegnare.
 */
@Composable
fun rememberChatAurora(mood: HaloMood, economy: ChatAmbientEconomy = rememberChatAmbientEconomy()): ChatAuroraState {
  val state = remember { ChatAuroraState() }
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion
  val on = !reducedMotion && economy != ChatAmbientEconomy.Off && mood.lightsAurora()
  LaunchedEffect(on) {
    state.presence.animateTo(
      targetValue = if (on) 1f else 0f,
      animationSpec = if (on) FluidMotion.fadeIn(AuroraFadeInMs) else FluidMotion.fadeOut(AuroraFadeOutMs),
    )
  }
  return state
}

/**
 * Il canvas dell'aurora, a schermo intero, da mettere **dentro** il fondale della chat: nella
 * registrazione del canvas, sotto la lista, e quindi dentro la rifrazione della barra e del
 * composer. Disegna solo mentre [ChatAuroraState.present]; a riposo e' un nodo vuoto.
 *
 * Tre colori del tema (accento, secondario, terziario) che girano piano nel ciclo; `Plus` su una
 * pagina scura, `SrcOver` su una chiara, deciso dalla luminanza della superficie e non dal nome del
 * tema. L'orologio va a velocita' 1: i periodi di [AuroraSpec] sono secondi veri, e un'aurora che
 * accelerasse con l'umore come fa l'alone sarebbe un fondo che si agita dietro le parole.
 *
 * Presenza, tempo e ampiezza si leggono **nel disegno**: a sessanta fotogrammi al secondo si
 * invalida questo canvas e basta, e nessun fratello ricompone. [level] e' una lambda per lo stesso
 * motivo: il microfono arriva a cinquanta hertz e lo legge il loop di frame, non la composizione.
 *
 * La forma (quante macchie, quanto larghe) si decide quando l'aurora si accende e si tiene finche'
 * non si spegne: l'economia cambia a meta' di uno scorrimento, e una macchia che sparisce o tutte
 * che cambiano raggio sotto il testo mentre Aria scrive e' esattamente il salto che non si vuole.
 * Il passo dell'orologio (un fotogramma si' e uno no) invece segue l'economia dal vivo: si vede
 * come fluidita', non come forma.
 */
@Composable
fun ChatAurora(
  state: ChatAuroraState,
  mood: HaloMood,
  level: () -> Float,
  modifier: Modifier = Modifier,
  economy: ChatAmbientEconomy = rememberChatAmbientEconomy(),
) {
  val colours = HaloColours.fromTheme()
  val cycle = remember(colours) { listOf(colours.accent, colours.secondary, colours.tertiary) }
  val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
  val present = state.present
  val spec = remember(present) { if (economy == ChatAmbientEconomy.Economy) AuroraSpec(blobCount = 4, radiusMax = 0.45f) else AuroraSpec() }
  val clock = rememberHaloClock(
    running = present,
    speed = 1f,
    economy = economy == ChatAmbientEconomy.Economy,
    target = {
      when (mood) {
        HaloMood.LISTENING -> ListeningFloor + ListeningRange * level().coerceIn(0f, 1f)
        HaloMood.WORKING, HaloMood.WRITING -> WorkingAmplitude
        else -> 0f
      }
    },
  )
  Canvas(modifier.fillMaxSize()) {
    val presence = state.presence.value
    if (presence > AuroraPresenceFloor) {
      drawAuroraField(clock.time, clock.amplitude, presence, cycle, spec, dark)
    }
  }
}
