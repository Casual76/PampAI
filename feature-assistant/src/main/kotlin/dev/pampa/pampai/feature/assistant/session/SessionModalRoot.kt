package dev.pampa.pampai.feature.assistant.session

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.ui.fluid.*

/** Real session root, also used by picker regression fixtures. Theme owns motion policy. */
@Composable
fun SessionModalRoot(visibleStamp: Long, content: @Composable () -> Unit) {
  // Reopening a VoiceInteractionSession must never restore an abandoned picker/prompt.
  key(visibleStamp) {
    val host = rememberFluidGlassModalHostState()
    val backdrop = rememberGlassBackdrop()
    CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdropSource(backdrop).fluidGlassModalObscured()) { content() }
        FluidGlassModalHost(host, backdrop)
      }
    }
  }
}
