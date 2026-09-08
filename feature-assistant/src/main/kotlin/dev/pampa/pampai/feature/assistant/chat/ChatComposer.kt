package dev.pampa.pampai.feature.assistant.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.GlassDefaults
import dev.antigravity.fluidengine.ui.fluid.GlassRole
import dev.antigravity.fluidengine.ui.fluid.GlassTint
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.fluid.glassControlSurface
import dev.antigravity.fluidengine.ui.fluid.glassSurface
import dev.antigravity.fluidengine.ui.fluid.rememberCombinedGlassBackdrop
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.runtime.VoiceEvent
import dev.pampa.pampai.feature.assistant.halo.HaloColours
import dev.pampa.pampai.feature.assistant.halo.haloBlendForTheme
import dev.pampa.pampai.feature.assistant.halo.rememberHaloClock

/**
 * La barra in fondo, nella forma delle chat che si usano: il campo sopra, e sotto la riga con il
 * "+", il modello che risponde e il microfono (o l'invio, o lo stop). Sopra la barra, quando ci
 * sono, le pillole: allegati, plugin scelto, "pensa piu' a fondo", modifica in corso.
 *
 * Il "+" apre un menu di vetro **sul tasto** (Fotocamera, Foto, File, Plugin, Pensa piu' a
 * fondo, Chat temporanea); il modello apre un pop-up sul suo chip. Entrambi vengono dal padrone di casa dei
 * modali alla radice, sullo stesso vetro della chat.
 *
 * Il vetro: la capsula rifrange [backdrop] (fondale e lista insieme) e **esporta** il proprio
 * materiale finito, cosi' i tasti tondi che ci stanno sopra piegano la capsula e non la pagina
 * tre strati piu' giu' — come fa la barra di `FluidScreen` con le sue azioni. Prima campionavano
 * la pagina direttamente, e ogni tasto era un buco nitido nel composer.
 *
 * @param resampleIntervalMillis ogni quanto, al massimo, la capsula rifa' la propria cattura; zero
 *   e' "quando serve". Sopra un fondale animato lo alza chi anima il fondale.
 */
@Composable
internal fun Composer(
  backdrop: GlassBackdropState,
  state: ChatUiState,
  editing: Message?,
  onSend: (String) -> Unit,
  onCancelEdit: () -> Unit,
  onStop: () -> Unit,
  onVoice: () -> Unit,
  onStopVoice: () -> Unit,
  onCancelVoice: () -> Unit,
  onStopSpeaking: () -> Unit,
  micLevel: kotlinx.coroutines.flow.StateFlow<dev.antigravity.fluidengine.ai.orchestrator.MicLevel>,
  partial: String?,
  speaking: Boolean,
  voiceEvents: kotlinx.coroutines.flow.SharedFlow<VoiceEvent>,
  draft: String?,
  onDraftConsumed: () -> Unit,
  onAttach: (android.net.Uri) -> Unit,
  onRemoveAttachment: (Int) -> Unit,
  onOpenSettings: () -> Unit,
  onProvider: (ProviderId) -> Unit,
  onThinking: (ThinkingLevel) -> Unit,
  thinkingAuto: Boolean,
  onThinkingAuto: (Boolean) -> Unit,
  plugins: List<PluginOption>,
  plugin: String?,
  onPlugin: (String?) -> Unit,
  deepNext: Boolean,
  onToggleDeep: () -> Unit,
  onAttachImage: (ByteArray, String) -> Unit,
  temporary: Boolean,
  onToggleTemporary: () -> Unit,
  resampleIntervalMillis: Long = 0L,
) {
  val context = LocalContext.current
  // Il materiale della capsula, pubblicato per i tasti che ci stanno sopra: una lente appoggiata
  // su vetro smerigliato mostra la smerigliatura, non la pagina sotto.
  val composerGlass = rememberGlassBackdrop()
  val controlBackdrop = rememberCombinedGlassBackdrop(backdrop, composerGlass)
  var text by rememberSaveable { mutableStateOf("") }
  LaunchedEffect(editing?.id) { editing?.let { text = it.text } }
  LaunchedEffect(draft) {
    if (draft != null) {
      text = draft
      onDraftConsumed()
    }
  }
  val busy = state.live?.isBusy == true
  val listening = state.live is AssistantState.Listening
  val transcribing = state.live is AssistantState.Transcribing
  // L'alone mentre ascolta: l'orologio segue il microfono (letto nel loop di frame, non in
  // composizione: lo `StateFlow` a cinquanta hertz non ricompone nessuno) e la presenza sfuma
  // dentro e fuori. Mentre trascrive rallenta e si assesta: la voce e' finita, si aspetta.
  val voiceActive = listening || transcribing
  val glowClock = rememberHaloClock(
    running = voiceActive,
    speed = if (transcribing) GlowTranscribingTempo else 1f,
    target = { if (transcribing) GlowTranscribingAmplitude else GlowFloorAmplitude + (1f - GlowFloorAmplitude) * micLevel.value.level.coerceIn(0f, 1f) },
  )
  val glow = animateFloatAsState(
    targetValue = if (voiceActive) 1f else 0f,
    animationSpec = if (voiceActive) FluidMotion.fadeIn(GlowFadeInMs) else FluidMotion.fadeOut(GlowFadeOutMs),
    label = "composerGlow",
  )
  val glowColours = HaloColours.fromTheme()
  val glowBlend = haloBlendForTheme()
  val focus = remember { FocusRequester() }
  // Il silenzio iniziale: la barra e' gia' tornata testo, qui si mette il cursore nel campo.
  LaunchedEffect(Unit) { voiceEvents.collect { if (it is VoiceEvent.InitialSilence) runCatching { focus.requestFocus() } } }
  val micGranted = remember { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
  val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onVoice() }
  val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris -> uris.forEach(onAttach) }
  val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(onAttach) }
  // L'anteprima basta: e' una foto per il modello, non per l'album. E non vuole ne' il permesso
  // della fotocamera ne' un FileProvider.
  val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
    if (bitmap != null) {
      val out = java.io.ByteArrayOutputStream()
      bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
      onAttachImage(out.toByteArray(), "foto.jpg")
    }
  }
  var plusMenu by remember { mutableStateOf(false) }
  var pluginPage by remember { mutableStateOf(false) }
  var modelMenu by remember { mutableStateOf(false) }
  // L'ancora dei due pop-up e' il composer intero, non il tasto: ancorati a un tasto in fondo allo
  // schermo si aprivano addosso al campo di testo. Contro il bordo alto del composer stanno sopra.
  var composerRect by remember { mutableStateOf<Rect?>(null) }
  // Il "+" invece si apre *su se stesso*: il menu parte dal suo rettangolo e ci ritorna.
  var plusRect by remember { mutableStateOf<Rect?>(null) }
  // Non il rettangolo intero ma il suo bordo alto, spesso un pixel: il pop-up nasce *contro*
  // l'ancora, e contro una riga sottile vuol dire sopra il composer, non a cavallo.
  val menuAnchor: () -> Rect? = { composerRect?.let { Rect(it.left, it.top - 4f, it.right, it.top) } }
  val chosenPlugin = plugins.firstOrNull { it.id == plugin }

  fun submit() {
    val query = text.trim()
    if ((query.isEmpty() && state.attachments.isEmpty()) || busy) return
    onSend(query)
    text = ""
  }

  Column {
    val pills = editing != null || state.attachments.isNotEmpty() || chosenPlugin != null || deepNext
    if (pills) {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)) {
        if (editing != null) Pill(Icons.Rounded.Edit, "Modifico il messaggio", accent = true, onClick = onCancelEdit)
        chosenPlugin?.let { Pill(Icons.Rounded.Extension, it.label, accent = true, onClick = { onPlugin(null) }) }
        if (deepNext) Pill(Icons.Rounded.Psychology, "Pensa piu' a fondo", accent = true, onClick = onToggleDeep)
        state.attachments.forEachIndexed { index, attachment -> AttachmentChip(attachment) { onRemoveAttachment(index) } }
      }
    }
    // La scatola esterna porta l'alone: le macchie stanno dietro al vetro e sbordano attorno, l'anello
    // sopra il bordo. Non clippa, e non deve: la luce vive fuori dalla capsula.
    Box(Modifier.composerListeningGlow(clock = glowClock, presence = { glow.value }, colours = glowColours, blend = glowBlend)) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .onGloballyPositioned { composerRect = it.boundsInRoot() }
          .glassSurface(
            state = backdrop,
            tint = composerTint(),
            shape = ComposerShape,
            role = ComposerRole,
            exports = composerGlass,
            resampleIntervalMillis = resampleIntervalMillis,
          )
          .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
      ) {
        if (voiceActive) {
          ComposerVoiceLine(partial = partial, transcribing = transcribing, amplitude = { glowClock.amplitude }, onTap = onCancelVoice, modifier = Modifier.fillMaxWidth())
        } else {
          ComposerField(
            value = text,
            onValueChange = { text = it },
            placeholder = when {
              !state.enabled -> "Aggiungi una chiave nelle impostazioni"
              busy -> "Sto rispondendo..."
              editing != null -> "Modifica e rinvia..."
              else -> "Chiedi ad Aria..."
            },
            enabled = !busy && state.enabled,
            onSend = { submit() },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
          )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          GlassRound(
            icon = Icons.Rounded.Add,
            description = "Allega o scegli",
            backdrop = controlBackdrop,
            modifier = Modifier.onGloballyPositioned { plusRect = it.boundsInRoot() },
            onClick = { pluginPage = false; plusMenu = true },
          )
          Spacer(Modifier.width(8.dp))
          ModelPill(label = modelLabel(state, thinkingAuto), onClick = { modelMenu = true })
          Spacer(Modifier.weight(1f))
          when {
            !state.enabled -> GlassRound(Icons.Rounded.ArrowUpward, "Impostazioni", controlBackdrop, onClick = onOpenSettings)
            listening -> GlassRound(Icons.Rounded.Stop, "Smetti di ascoltare", controlBackdrop, tint = MaterialTheme.colorScheme.error, onClick = onStopVoice)
            busy -> GlassRound(Icons.Rounded.Stop, "Ferma", controlBackdrop, onClick = onStop)
            speaking && text.isBlank() -> GlassRound(Icons.Rounded.VolumeOff, "Zitta", controlBackdrop, onClick = onStopSpeaking)
            text.isBlank() && state.attachments.isEmpty() -> GlassRound(Icons.Rounded.Mic, "Parla", controlBackdrop, onClick = { if (micGranted) onVoice() else micLauncher.launch(Manifest.permission.RECORD_AUDIO) })
            else -> GlassRound(Icons.Rounded.ArrowUpward, "Invia", controlBackdrop, tint = MaterialTheme.colorScheme.primary, onClick = { submit() })
          }
        }
      }
    }
  }

  // Il menu del "+": il tasto *diventa* il menu (presentazione `Expand`: parte dal suo
  // rettangolo, cresce con le molle della Fluid-physics, e ci ritorna quando si sceglie).
  // Due pagine: le azioni, e la scelta del plugin.
  FluidGlassModalPortal(
    visible = plusMenu,
    onDismissRequest = { plusMenu = false; pluginPage = false },
    origin = { plusRect },
    presentation = FluidGlassModalPresentation.Expand,
    paneTitle = if (pluginPage) "Plugin" else "Allega",
  ) {
    Column(Modifier.width(264.dp).padding(vertical = 4.dp)) {
      if (!pluginPage) {
        MenuRow(Icons.Rounded.PhotoCamera, "Fotocamera") { plusMenu = false; cameraLauncher.launch(null) }
        MenuRow(Icons.Rounded.Image, "Foto") { plusMenu = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        MenuRow(Icons.Rounded.AttachFile, "File") { plusMenu = false; filePicker.launch(arrayOf("application/pdf", "text/*", "image/*")) }
        MenuDivider()
        MenuRow(Icons.Rounded.Extension, "Plugin", detail = chosenPlugin?.label, trailing = Icons.Rounded.ChevronRight) { pluginPage = true }
        MenuRow(Icons.Rounded.Psychology, "Pensa piu' a fondo", detail = "Livello profondo e ragionamento alto", checked = deepNext) { onToggleDeep(); plusMenu = false }
        // Non un interruttore su questa chat: apre una chat nuova, temporanea (e spegnendolo ne
        // apre una normale). Una conversazione gia' scritta non puo' diventare "come se non fosse
        // mai stata scritta", quindi la scelta si fa prima di parlare, e la spunta dice dove si e'.
        MenuRow(Icons.Rounded.VisibilityOff, "Chat temporanea", detail = "Non resta in cronologia ne' in memoria", checked = temporary) { onToggleTemporary(); plusMenu = false }
      } else {
        MenuRow(Icons.Rounded.ArrowBack, "Indietro") { pluginPage = false }
        MenuDivider()
        MenuRow(Icons.Rounded.Close, "Nessun plugin", detail = "Aria sceglie da sola", checked = plugin == null) { onPlugin(null); plusMenu = false; pluginPage = false }
        plugins.forEach { option ->
          MenuRow(Icons.Rounded.Extension, option.label, detail = option.hint.take(48), checked = option.id == plugin) { onPlugin(option.id); plusMenu = false; pluginPage = false }
        }
      }
    }
  }

  // Il modello: chi risponde e quanto ci pensa, in un pop-up sul chip.
  FluidGlassModalPortal(
    visible = modelMenu,
    onDismissRequest = { modelMenu = false },
    origin = menuAnchor,
    presentation = FluidGlassModalPresentation.Popover,
    paneTitle = "Modello",
  ) {
    ModelMenu(
      state = state,
      thinkingAuto = thinkingAuto,
      onProvider = { onProvider(it); modelMenu = false },
      onEffort = { effort ->
        when (effort) {
          Effort.AUTO -> onThinkingAuto(true)
          Effort.LOW -> { onThinkingAuto(false); onThinking(ThinkingLevel.LOW) }
          Effort.MEDIUM -> { onThinkingAuto(false); onThinking(ThinkingLevel.MEDIUM) }
          Effort.HIGH -> { onThinkingAuto(false); onThinking(ThinkingLevel.HIGH) }
        }
      },
    )
  }
}

/**
 * L'alone in ascolto: quanto ci mette a comparire e a sparire. In uscita un po' piu' lungo, cosi'
 * quando si smette di parlare la luce si posa invece di spegnersi.
 */
private const val GlowFadeInMs = 220
private const val GlowFadeOutMs = 260

/** L'ampiezza dell'alone nel silenzio: fra due sillabe non si spegne. Con la voce va a uno. */
private const val GlowFloorAmplitude = 0.25f

/** Mentre trascrive l'alone si assesta basso e va a un terzo del passo: la voce e' finita, si aspetta. */
private const val GlowTranscribingAmplitude = 0.30f
private const val GlowTranscribingTempo = 0.35f

/** Il raggio della capsula. Preesistente e non un token `FluidRadius`: cambiarlo e' una scelta visiva. */
internal val ComposerCorner: Dp = 26.dp

/** La sagoma della capsula, condivisa con chi deve nascerci dentro o ritornarci (i menu del "+"). */
internal val ComposerShape = ContinuousCornerShape(ComposerCorner)

/**
 * `Floating`, con riserva. E' il ruolo giusto per una capsula che galleggia sulla chat: la lente
 * e' la stessa del `Modal` (19/29 dp contro 20/28), cambia la sfocatura, 1.8 contro 3.5, e con la
 * pagina rifratta sotto la piu' leggera e' quella che lascia vedere che *c'e'* una pagina.
 * Sull'emulatore ogni build con `Floating` su una superficie di questa misura finiva in ANR nel
 * disegno ("main thread affamato", non un loop), e quelle con `Modal` no: sul telefono va provato,
 * e il ripiego e' `GlassRole.Modal`, una parola.
 */
private val ComposerRole = GlassRole.Floating

/**
 * Quanto del film dei `floatingTint` resta sulla capsula. Il 52% (chiaro) era pensato per una
 * pillola di navigazione con etichette di otto pixel sopra contenuto arbitrario; con la pagina
 * rifratta sotto e le parole grandi del campo, era un velo bianco che copriva il vetro. Quattro
 * quinti: 0.52 -> 0.42 in chiaro, 0.48 -> 0.38 in scuro. Il ripiego senza `RenderEffect` e il
 * filo del bordo restano quelli della famiglia.
 */
private const val ComposerFilmShare = 0.80f

@Composable
private fun composerTint(): GlassTint {
  val base = GlassDefaults.floatingTint()
  return base.copy(overlay = base.overlay.copy(alpha = base.overlay.alpha * ComposerFilmShare))
}

/**
 * Il campo del composer: le parole direttamente sul vetro.
 *
 * `FluidTextField` disegna un pozzo grigio sotto il testo, giusto in un modulo e sbagliato qui: un
 * rettangolo pieno dentro una capsula di vetro e' quello che fa leggere il vetro come "una
 * trasparenza". Nelle chat che si usano il testo sta sulla superficie, e il bordo della capsula e'
 * l'unico contorno.
 *
 * @param maxLines quante righe il campo puo' crescere prima di scorrere; sei e' il composer della
 *   chat, chi lo riusa altrove sceglie le sue.
 */
@Composable
internal fun ComposerField(
  value: String,
  onValueChange: (String) -> Unit,
  placeholder: String,
  enabled: Boolean,
  onSend: () -> Unit,
  modifier: Modifier = Modifier,
  maxLines: Int = 6,
) {
  val scheme = MaterialTheme.colorScheme
  BasicTextField(
    value = value,
    onValueChange = onValueChange,
    enabled = enabled,
    maxLines = maxLines,
    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
    cursorBrush = SolidColor(scheme.primary),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
    keyboardActions = KeyboardActions(onSend = { onSend() }),
    modifier = modifier.padding(horizontal = 10.dp, vertical = 12.dp),
    decorationBox = { inner ->
      Box {
        if (value.isEmpty()) {
          Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant.copy(alpha = 0.75f))
        }
        inner()
      }
    },
  )
}

/** Il chip del modello: una pillola bassa, che non ruba la riga al campo di testo. */
@Composable
private fun ModelPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Row(
    modifier = modifier
      .height(30.dp)
      .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), FluidCapsuleShape)
      .fluidPressable(onClick = onClick, role = Role.Button, haptic = null)
      .padding(start = 12.dp, end = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.width(2.dp))
    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
  }
}

/** Una pillola sopra la barra: un allegato, il plugin, la modalita'. Toccarla la toglie. */
@Composable
private fun Pill(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
  val bg = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
  val fg = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
  Row(
    modifier = Modifier
      .height(30.dp)
      .background(bg, FluidCapsuleShape)
      .fluidPressable(onClick = onClick, role = Role.Button, haptic = null)
      .padding(start = 10.dp, end = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.width(4.dp))
    Icon(Icons.Rounded.Close, contentDescription = "Togli", tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
  }
}

/**
 * Un tasto tondo di vetro, come quelli della barra nella sessione: il "+", il microfono, l'invio,
 * lo stop. Lo stesso disco per tutti, cambia solo l'icona e, quando serve, il colore. Il
 * [backdrop] e' quello che il disco rifrange: sul composer e' la pila pagina + capsula.
 */
@Composable
internal fun GlassRound(
  icon: ImageVector,
  description: String,
  backdrop: GlassBackdropState,
  modifier: Modifier = Modifier,
  tint: Color = MaterialTheme.colorScheme.onSurface,
  onClick: () -> Unit,
) {
  Box(
    modifier = modifier
      .size(40.dp)
      .glassControlSurface(backdrop = backdrop, shape = FluidCapsuleShape)
      .fluidPressable(onClick = onClick, role = Role.Button),
    contentAlignment = Alignment.Center,
  ) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
  }
}

@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
  Pill(
    icon = if (attachment.kind == AttachmentKind.IMAGE) Icons.Rounded.Image else Icons.Rounded.AttachFile,
    label = attachment.name.take(22),
    onClick = onRemove,
  )
}
