package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.pampa.pampai.core.assistant.db.Attachment
import java.io.File

/**
 * Un'immagine allegata a tutto schermo: pizzico per ingrandire, doppio tocco per tornare, un
 * tocco o indietro per chiudere. Prima l'allegato era un'etichetta che non si apriva.
 */
@Composable
internal fun ImageViewer(attachment: Attachment, onDismiss: () -> Unit) {
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
      scale = (scale * zoom).coerceIn(1f, 5f)
      offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
      Modifier
        .fillMaxSize()
        .background(Color.Black)
        .pointerInput(Unit) {
          detectTapGestures(
            onTap = { if (scale == 1f) onDismiss() },
            onDoubleTap = {
              scale = 1f
              offset = Offset.Zero
            },
          )
        },
      contentAlignment = Alignment.Center,
    ) {
      AsyncImage(
        model = File(attachment.path),
        contentDescription = attachment.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
          .fillMaxSize()
          .transformable(transform)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
          },
      )
      Box(
        Modifier
          .align(Alignment.TopEnd)
          .safeDrawingPadding()
          .padding(8.dp)
          .size(48.dp)
          .background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape)
          .fluidPressable(onClick = onDismiss, role = Role.Button),
        contentAlignment = Alignment.Center,
      ) {
        Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = Color.White)
      }
    }
  }
}
