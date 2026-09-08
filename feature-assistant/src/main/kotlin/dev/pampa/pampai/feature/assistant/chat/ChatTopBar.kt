package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassFalloff
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassSurface

/**
 * La barra in cima: il menu, il titolo della conversazione, una chat nuova.
 *
 * Non una lastra: un blur **progressivo**, pieno dove stanno titolo e tasti e che sfuma a niente
 * un po' piu' in basso, come le barre di iOS. La zona sfumata si prende 28 dp oltre la riga dei
 * tasti, cosi' la sfumatura succede *sotto* il titolo e non attraverso: era quello che faceva
 * filtrare il testo della pagina dentro alle parole. Una lastra uniforme, provata, aveva un bordo
 * netto in fondo, e in una chat il bordo netto e' esattamente la cosa che stona.
 *
 * E come la barra di `FluidScreen` non c'e' finche' la lista sta in cima: [intensity] a zero e'
 * materiale assente, e si addensa nei primi 64 dp di scorrimento, quando qualcosa passa davvero
 * sotto l'orologio. Sopra una chat vuota una lastra piena era solo un film bianco. Il materiale e'
 * quello delle barre ([GlassDefaults.barTint]), non quello dei modali: e' chrome, non una finestra.
 * Le icone non sono vetro, quindi la barra non esporta il proprio materiale.
 *
 * @param temporary la chat aperta e' temporanea: sotto il titolo compare il distintivo.
 * @param intensity 0 = vetro assente, 1 = materiale pieno; lo guida chi conosce lo scorrimento.
 * @param resampleIntervalMillis ogni quanto, al massimo, la barra rifa' la propria cattura; zero e'
 *   "quando serve". Sopra un fondale animato lo alza chi anima il fondale.
 */
@Composable
internal fun ChatTopBar(
  title: String,
  facet: String?,
  backdrop: GlassBackdropState,
  onMenu: () -> Unit,
  onNew: (() -> Unit)?,
  temporary: Boolean = false,
  intensity: () -> Float = { 1f },
  resampleIntervalMillis: Long = 0L,
) {
  Box(Modifier.fillMaxWidth()) {
    // Il materiale da solo, su un fratello vuoto, e i controlli sopra: come `FluidTopBar`. Il vetro
    // a intensita' zero non disegna niente, e la sfumatura verso il basso e' una maschera su tutto
    // cio' che sta dentro; con le icone figlie del vetro sparivano a lista ferma e sbiadivano con
    // la rampa. Cosi' restano opache e leggibili qualunque cosa faccia il materiale.
    Box(
      Modifier
        .matchParentSize()
        .glassSurface(
          state = backdrop,
          tint = GlassDefaults.barTint(),
          shape = RectangleShape,
          falloff = GlassFalloff.FadeDown,
          role = GlassRole.Bar,
          intensity = intensity,
          resampleIntervalMillis = resampleIntervalMillis,
        ),
    )
    Column(
      Modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .padding(bottom = 28.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(56.dp)
          .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BarIcon(Icons.Rounded.Menu, "Conversazioni", onMenu)
        Column(
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (title.isNotBlank()) {
            Text(
              text = title,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (temporary) TemporaryBadge()
          facet?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
        if (onNew != null) BarIcon(Icons.Rounded.Add, "Nuova conversazione", onNew) else Spacer(Modifier.size(40.dp))
      }
    }
  }
}

/**
 * Il distintivo della chat temporanea: una capsula piccola sotto il titolo, con l'occhio sbarrato
 * e la parola. Il titolo resta quello che e' — questa e' una nota sullo stato della chat, non un
 * altro nome — e il grigio delle etichette basta: e' una cosa che l'utente ha scelto lui, non un
 * avviso. Sta sempre in vista perche' e' l'unica differenza visibile da una chat che resta.
 */
@Composable
private fun TemporaryBadge() {
  Row(
    modifier = Modifier
      .padding(top = 2.dp)
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f), FluidCapsuleShape)
      .padding(horizontal = 8.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Rounded.VisibilityOff,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(13.dp),
    )
    Spacer(Modifier.width(4.dp))
    Text(
      text = "Temporanea",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
  }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit, primary: Boolean = false) {
  Box(
    modifier = Modifier
      .size(40.dp)
      .fluidPressable(onClick = onClick, role = Role.Button),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = description,
      tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
      modifier = Modifier.size(22.dp),
    )
  }
}
