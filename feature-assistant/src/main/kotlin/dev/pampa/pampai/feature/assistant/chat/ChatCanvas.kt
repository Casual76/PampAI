package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * La velatura sotto la conversazione: due aloni morbidi nei colori del marchio.
 *
 * Il fondo di una chat deve restare fondo — il testo ci sta sopra per pagine intere — ma grigio
 * liscio non e' questa app. Due macchie ferme, appena accennate, agli angoli che il testo non
 * occupa mai.
 *
 * Sta sul **fondale** ([ChatBackdrops.canvas]), nella catena *dopo* `glassBackdropSource` e dopo
 * il fondo opaco: e' cosi' che entra davvero nella registrazione, e il vetro della barra e del
 * composer la rifrange invece di galleggiarci sopra. Nella registrazione del corpo non ci deve
 * stare: quella e' della lista, trasparente, e la velatura e' una cosa del fondale.
 *
 * Le alpha sono basse di proposito (0.10 / 0.16 / 0.14, giu' da 0.14 / 0.22 / 0.18): sul telefono
 * in tema chiaro la meta' alta della pagina leggeva "lilla", non "bianco con un accenno", e sopra
 * ci arrivera' anche l'aurora. Il fondo di una chat resta fondo.
 */
@Composable
internal fun Modifier.chatWash(): Modifier {
  val warm = MaterialTheme.colorScheme.primary
  val cool = MaterialTheme.colorScheme.tertiary
  return this.drawBehind {
    // Un velo verticale dall'alto, poi due aloni ai due angoli che il testo non occupa mai.
    drawRect(Brush.verticalGradient(listOf(warm.copy(alpha = 0.10f), Color.Transparent), startY = 0f, endY = size.height * 0.55f))
    val topCentre = Offset(size.width * 0.88f, size.height * 0.08f)
    val topRadius = size.width * 0.95f
    drawCircle(
      brush = Brush.radialGradient(listOf(warm.copy(alpha = 0.16f), Color.Transparent), center = topCentre, radius = topRadius),
      radius = topRadius,
      center = topCentre,
    )
    val bottomCentre = Offset(size.width * 0.06f, size.height * 0.82f)
    val bottomRadius = size.width * 1.05f
    drawCircle(
      brush = Brush.radialGradient(listOf(cool.copy(alpha = 0.14f), Color.Transparent), center = bottomCentre, radius = bottomRadius),
      radius = bottomRadius,
      center = bottomCentre,
    )
  }
}
