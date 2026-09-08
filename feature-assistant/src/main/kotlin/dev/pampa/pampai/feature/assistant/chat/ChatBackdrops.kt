package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.rememberCombinedGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop

/**
 * Le tre registrazioni della chat, come le due di FluidScreen: il fondale (opaco: fondo e
 * velatura, registrato per primo), il corpo (la lista, trasparente, registrato a parte cosi' un
 * fondale animato non rifa' mai il testo), e la loro pila, che e' quella che barra, composer,
 * modali e notifiche rifrangono. Una registrazione sola con il fondo fuori dalla sorgente era il
 * "vetro che non sfoca": il vetro campionava testo su trasparenza.
 */
@Stable
class ChatBackdrops(val canvas: GlassBackdropState, val body: GlassBackdropState, val chrome: GlassBackdropState)

@Composable
fun rememberChatBackdrops(): ChatBackdrops {
  val canvas = rememberGlassBackdrop()
  val body = rememberGlassBackdrop()
  val chrome = rememberCombinedGlassBackdrop(canvas, body)
  return remember(canvas, body, chrome) { ChatBackdrops(canvas, body, chrome) }
}
