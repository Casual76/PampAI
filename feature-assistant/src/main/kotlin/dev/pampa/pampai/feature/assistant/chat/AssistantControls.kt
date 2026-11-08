package dev.pampa.pampai.feature.assistant.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.*

/** Shared picker anchor. The caller owns catalog, selection and menu presentation. */
@Composable
internal fun AssistantModelControl(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, backdrop: GlassBackdropState? = null) {
  Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
    .then(if (backdrop != null) Modifier.glassSurface(state = backdrop, tint = GlassDefaults.barTint(),
      shape = ContinuousCornerShape(FluidRadius.Control), role = GlassRole.Interactive) else Modifier)
    .semantics { contentDescription = "Modello: $label" }
    .fluidPressable(onClick = onClick, role = Role.Button, pressedScale = 1f)
    .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
