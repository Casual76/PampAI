package dev.pampa.pampai.feature.assistant.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.antigravity.fluidengine.ai.keys.ThinkingLevel
import dev.antigravity.fluidengine.ai.orchestrator.AssistantState
import dev.antigravity.fluidengine.ai.orchestrator.MicLevel
import dev.antigravity.fluidengine.ai.provider.ProviderId
import dev.antigravity.fluidengine.aiui.chat.FluidComposer
import dev.antigravity.fluidengine.aiui.chat.FluidComposerChip
import dev.antigravity.fluidengine.aiui.chat.FluidComposerPill
import dev.antigravity.fluidengine.aiui.chat.FluidComposerVoiceLine
import dev.antigravity.fluidengine.aiui.chat.FluidGlassRoundButton
import dev.antigravity.fluidengine.aiui.chat.fluidComposerAction
import dev.antigravity.fluidengine.aiui.chat.rememberFluidListeningGlow
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPortal
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalPresentation
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.pampa.pampai.core.assistant.attachments.PendingAttachment
import dev.pampa.pampai.core.assistant.db.AttachmentKind
import dev.pampa.pampai.core.assistant.db.Message
import dev.pampa.pampai.core.assistant.runtime.VoiceEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Cio' che il composer ricorda fra una ricomposizione e l'altra: il testo nel campo e la domanda
 * che si sta correggendo. Sta fuori dal composer perche' anche la pagina ci scrive — un
 * suggerimento toccato mentre Aria lavora finisce nel campo, la pressione lunga su una bolla apre
 * "modifica e rinvia", una domanda tolta dalla coda torna qui — e perche' gli screenshot possano
 * costruire un composer con una riga sola.
 *
 * Il testo si salva con la pagina (una rotazione non lo perde); la modifica in corso no: e' un
 * messaggio intero, e dopo una rotazione ricominciare la correzione costa un tocco.
 */
@Stable
internal class ComposerState(text: String = "") {
  var text by mutableStateOf(text)
  var editing by mutableStateOf<Message?>(null)
    private set

  /** "Modifica e rinvia": il testo della domanda nel campo, e il composer sa a chi rispondera'. */
  fun startEditing(message: Message) {
    editing = message
    text = message.text
  }

  /** Niente correzione: se il campo e' ancora la domanda di prima si svuota, se l'ha cambiata resta. */
  fun cancelEditing() {
    val original = editing?.text
    editing = null
    if (text == original) text = ""
  }

  /** Un testo che arriva da fuori si aggiunge a quello gia' scritto, non lo cancella. */
  fun fill(value: String) {
    text = if (text.isBlank()) value else text.trimEnd() + "\n\n" + value
  }

  /** La domanda e' partita (o e' in coda): campo vuoto, nessuna correzione in corso. */
  fun sent() {
    text = ""
    editing = null
  }

  companion object {
    val Saver: Saver<ComposerState, String> = Saver(save = { it.text }, restore = { ComposerState(it) })
  }
}

@Composable
internal fun rememberComposerState(): ComposerState = rememberSaveable(saver = ComposerState.Saver) { ComposerState() }

/** Cosa il composer mostra e non decide lui: la voce che legge, l'impegno, il plugin, le modalita'. */
@Immutable
internal data class ComposerModel(
  val speaking: Boolean = false,
  val thinkingAuto: Boolean = true,
  val plugins: List<PluginOption> = emptyList(),
  val plugin: String? = null,
  val deepNext: Boolean = false,
  val temporary: Boolean = false,
)

/**
 * I flussi che il composer legge da se', ciascuno nel posto piu' piccolo possibile: il microfono
 * nel loop di frame dell'alone, le parole parziali nella riga della voce, gli esiti dell'ascolto in
 * un effetto legato al ciclo di vita. Nessuno ricompone la pagina.
 */
@Stable
internal class ComposerFeeds(
  val micLevel: StateFlow<MicLevel> = MutableStateFlow(MicLevel()),
  val partial: StateFlow<String?> = MutableStateFlow(null),
  val voiceEvents: Flow<VoiceEvent> = emptyFlow(),
)

/**
 * Cosa fa il composer quando lo si tocca. [onSend] torna false se la domanda non e' partita ne' e'
 * finita in coda (Aria spenta con le chiavi): allora il testo resta nel campo.
 */
@Stable
internal class ComposerActions(
  val onSend: (String) -> Boolean = { true },
  val onStop: () -> Unit = {},
  val onVoice: () -> Unit = {},
  val onStopVoice: () -> Unit = {},
  val onCancelVoice: () -> Unit = {},
  val onStopSpeaking: () -> Unit = {},
  val onAttach: (Uri) -> Unit = {},
  val onRemoveAttachment: (Int) -> Unit = {},
  val onOpenSettings: () -> Unit = {},
  val onProvider: (ProviderId) -> Unit = {},
  val onThinking: (ThinkingLevel) -> Unit = {},
  val onThinkingAuto: (Boolean) -> Unit = {},
  val onPlugin: (String?) -> Unit = {},
  val onToggleDeep: () -> Unit = {},
  val onToggleTemporary: () -> Unit = {},
)

/**
 * La barra in fondo, nella forma delle chat che si usano: e' il `FluidComposer` dell'engine (campo,
 * capsula di vetro, alone in ascolto, tasto a destra: microfono, invio, stop, zitta), e qui restano
 * gli slot che sono di Aria: il "+" con le sue due pagine (allegati, plugin, "pensa piu' a fondo",
 * chat temporanea), il chip del modello, e le pillole sopra (allegati, plugin scelto, modifica in
 * corso, la strada per le impostazioni quando manca una chiave).
 *
 * Il campo e' sempre usabile. Senza chiavi rispondono i comandi rapidi ("timer di 10 minuti"), che
 * girano sul telefono; il resto finisce nella risposta "nessuna chiave", con la pillola che porta
 * alle impostazioni. Mentre Aria lavora si scrive e si manda lo stesso: la domanda va in coda e
 * parte quando ha finito (accanto all'invio resta lo stop).
 *
 * Il "+" apre un menu di vetro **sul tasto** (Fotocamera, Foto, File, Plugin, Pensa piu' a
 * fondo, Chat temporanea); il modello apre un pop-up sul suo chip. Entrambi vengono dal padrone di casa dei
 * modali alla radice, sullo stesso vetro della chat.
 *
 * @param resampleIntervalMillis ogni quanto, al massimo, la capsula rifa' la propria cattura; zero
 *   e' "quando serve". Sopra un fondale animato lo alza chi anima il fondale.
 */
@Composable
internal fun Composer(
  backdrop: GlassBackdropState,
  state: ChatUiState,
  composer: ComposerState,
  model: ComposerModel = ComposerModel(),
  feeds: ComposerFeeds = remember { ComposerFeeds() },
  actions: ComposerActions = remember { ComposerActions() },
  resampleIntervalMillis: Long = 0L,
) {
  val context = LocalContext.current
  val text = composer.text
  val editing = composer.editing
  val busy = state.anyBusy
  val listening = state.live is AssistantState.Listening
  val transcribing = state.live is AssistantState.Transcribing
  // La riga della voce (e l'alone) ci sono anche mentre trascrive: per il tasto a destra e' ancora
  // "ascolto", e un tocco ferma la trascrizione come prima (la risposta e' di `onStop`, non di `onStopVoice`).
  val voiceActive = listening || transcribing
  val glow = rememberFluidListeningGlow(listening = listening, transcribing = transcribing, level = { feeds.micLevel.value.level })
  val focus = remember { FocusRequester() }
  // Il silenzio iniziale: la barra e' gia' tornata testo, qui si mette il cursore nel campo. Solo
  // con la chat davanti (STARTED): prima l'effetto restava in ascolto anche con l'app dietro, e un
  // silenzio nella sessione del tasto di accensione dava il fuoco al campo dell'app nascosta.
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(lifecycle, feeds.voiceEvents) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      feeds.voiceEvents.collect { if (it is VoiceEvent.InitialSilence) runCatching { focus.requestFocus() } }
    }
  }
  val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris -> uris.forEach(actions.onAttach) }
  val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(actions.onAttach) }
  // La foto intera, non l'anteprima: l'anteprima e' una miniatura di pochi pixel, e una pagina di
  // libro o uno scontrino fotografati cosi' non si leggono. Il file lo riempie l'app della
  // fotocamera (senza permesso della fotocamera per noi) e poi passa dalla via degli allegati,
  // che la rimpicciolisce a una misura buona per il modello.
  var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
  val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
    val uri = cameraUri
    if (saved && uri != null) actions.onAttach(uri)
    cameraUri = null
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
  val chosenPlugin = model.plugins.firstOrNull { it.id == model.plugin }

  // Non si ignora piu' niente: con Aria al lavoro la domanda va in coda (lo decide chi riceve
  // `onSend`), e il campo si svuota solo se e' partita o in coda davvero.
  fun submit() {
    val query = text.trim()
    if (query.isEmpty() && state.attachments.isEmpty()) return
    if (actions.onSend(query)) composer.sent()
  }

  // La strada per le impostazioni resta in vista quando serve: senza chiavi (i comandi rapidi
  // funzionano, il resto no) e con Aria spenta.
  val settingsPill = when {
    !state.hasKeys -> "Aggiungi una chiave"
    state.blockedByConsent -> "Aria e' spenta: accendila"
    else -> null
  }
  val hasPills = editing != null || state.attachments.isNotEmpty() || chosenPlugin != null || model.deepNext || settingsPill != null
  val hasInput = text.isNotBlank() || state.attachments.isNotEmpty()

  FluidComposer(
    value = text,
    onValueChange = { composer.text = it },
    backdrop = backdrop,
    action = fluidComposerAction(hasInput = hasInput, busy = busy, listening = voiceActive, speaking = model.speaking),
    onSend = { submit() },
    placeholder = when {
      editing != null -> AriaChatStrings.composerEditing
      // Si scrive anche mentre Aria risponde: la prossima domanda si prepara leggendo
      // questa, e con Invio va in coda.
      busy -> AriaChatStrings.composerBusyPlaceholder
      else -> AriaChatStrings.composerPlaceholder
    },
    strings = AriaChatStrings,
    onStop = actions.onStop,
    onVoice = actions.onVoice,
    // Trascrivendo, il tasto "ferma" e' lo stop della domanda (come prima), non la fine dell'ascolto.
    onStopListening = { if (transcribing) actions.onStop() else actions.onStopVoice() },
    onMute = actions.onStopSpeaking,
    pills = if (!hasPills) null else ({
      settingsPill?.let { FluidComposerPill(Icons.Rounded.Key, it, accent = true, removable = false, trailingLink = Icons.Rounded.ChevronRight, onClick = actions.onOpenSettings) }
      if (editing != null) FluidComposerPill(Icons.Rounded.Edit, "Modifico il messaggio", accent = true, onClick = { composer.cancelEditing() })
      chosenPlugin?.let { FluidComposerPill(Icons.Rounded.Extension, it.label, accent = true, onClick = { actions.onPlugin(null) }) }
      if (model.deepNext) FluidComposerPill(Icons.Rounded.Psychology, "Pensa piu' a fondo", accent = true, onClick = actions.onToggleDeep)
      state.attachments.forEachIndexed { index, attachment -> AttachmentChip(attachment) { actions.onRemoveAttachment(index) } }
    }),
    leading = { controlBackdrop ->
      FluidGlassRoundButton(
        icon = Icons.Rounded.Add,
        description = "Allega o scegli",
        backdrop = controlBackdrop,
        modifier = Modifier.onGloballyPositioned { plusRect = it.boundsInRoot() },
        onClick = { pluginPage = false; plusMenu = true },
      )
    },
    chips = { FluidComposerChip(label = modelLabel(state, model.thinkingAuto), onClick = { modelMenu = true }) },
    voiceLine = { ChatVoiceLine(partial = feeds.partial, transcribing = transcribing, amplitude = { glow.clock.amplitude }, onTap = actions.onCancelVoice, modifier = Modifier.fillMaxWidth()) },
    glow = glow,
    focusRequester = focus,
    onBoundsChange = { composerRect = it },
    resampleIntervalMillis = resampleIntervalMillis,
  )

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
        MenuRow(Icons.Rounded.PhotoCamera, "Fotocamera") { plusMenu = false; runCatching { AttachmentFiles.newCameraUri(context).also { cameraUri = it } }.getOrNull()?.let { cameraLauncher.launch(it) } }
        MenuRow(Icons.Rounded.Image, "Foto") { plusMenu = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        MenuRow(Icons.Rounded.AttachFile, "File") { plusMenu = false; filePicker.launch(arrayOf("application/pdf", "text/*", "image/*")) }
        MenuDivider()
        MenuRow(Icons.Rounded.Extension, "Plugin", detail = chosenPlugin?.label, trailing = Icons.Rounded.ChevronRight) { pluginPage = true }
        MenuRow(Icons.Rounded.Psychology, "Pensa piu' a fondo", detail = "Livello profondo e ragionamento alto", checked = model.deepNext) { actions.onToggleDeep(); plusMenu = false }
        // Non un interruttore su questa chat: apre una chat nuova, temporanea (e spegnendolo ne
        // apre una normale). Una conversazione gia' scritta non puo' diventare "come se non fosse
        // mai stata scritta", quindi la scelta si fa prima di parlare, e la spunta dice dove si e'.
        MenuRow(Icons.Rounded.VisibilityOff, "Chat temporanea", detail = "Non resta in cronologia ne' in memoria", checked = model.temporary) { actions.onToggleTemporary(); plusMenu = false }
      } else {
        MenuRow(Icons.Rounded.ArrowBack, "Indietro") { pluginPage = false }
        MenuDivider()
        MenuRow(Icons.Rounded.Close, "Nessun plugin", detail = "Aria sceglie da sola", checked = model.plugin == null) { actions.onPlugin(null); plusMenu = false; pluginPage = false }
        model.plugins.forEach { option ->
          MenuRow(Icons.Rounded.Extension, option.label, detail = option.hint.take(48), checked = option.id == model.plugin) { actions.onPlugin(option.id); plusMenu = false; pluginPage = false }
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
      thinkingAuto = model.thinkingAuto,
      onProvider = { actions.onProvider(it); modelMenu = false },
      onEffort = { effort ->
        when (effort) {
          Effort.AUTO -> actions.onThinkingAuto(true)
          Effort.LOW -> { actions.onThinkingAuto(false); actions.onThinking(ThinkingLevel.LOW) }
          Effort.MEDIUM -> { actions.onThinkingAuto(false); actions.onThinking(ThinkingLevel.MEDIUM) }
          Effort.HIGH -> { actions.onThinkingAuto(false); actions.onThinking(ThinkingLevel.HIGH) }
        }
      },
    )
  }
}

/**
 * La riga della voce della chat: le parole parziali si raccolgono **qui**, non in cima alla pagina.
 * Il riconoscitore riemette la frase piu' volte al secondo, e raccolte nella pagina ricomponevano
 * la chat intera a ogni parola; qui ricompongono solo questa riga.
 */
@Composable
private fun ChatVoiceLine(partial: StateFlow<String?>, transcribing: Boolean, amplitude: () -> Float, onTap: () -> Unit, modifier: Modifier = Modifier) {
  val words by partial.collectAsStateWithLifecycle()
  FluidComposerVoiceLine(partial = words, transcribing = transcribing, amplitude = amplitude, onTap = onTap, modifier = modifier, strings = AriaAssistantStrings)
}

@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
  FluidComposerPill(
    icon = if (attachment.kind == AttachmentKind.IMAGE) Icons.Rounded.Image else Icons.Rounded.AttachFile,
    label = attachment.name.take(22),
    onClick = onRemove,
  )
}
