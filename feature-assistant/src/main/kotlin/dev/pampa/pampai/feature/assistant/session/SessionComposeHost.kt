package dev.pampa.pampai.feature.assistant.session

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Compose dentro una finestra che non e' un'Activity (la `VoiceInteractionSession`): un contenitore
 * che fa da padrone del ciclo di vita, dei ViewModel e dello stato salvato, perche' `ComposeView`
 * li cerca risalendo l'albero e senza non parte. Il ciclo di vita lo muove la sessione:
 * [resume] quando si mostra, [pause] quando si nasconde, [dispose] quando muore.
 */
class SessionComposeHost(context: Context) : FrameLayout(context), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

  private val registry = LifecycleRegistry(this)
  private val store = ViewModelStore()
  private val savedState = SavedStateRegistryController.create(this)
  private val composeView = ComposeView(context)

  init {
    savedState.performRestore(null)
    registry.currentState = Lifecycle.State.CREATED
    setViewTreeLifecycleOwner(this)
    setViewTreeViewModelStoreOwner(this)
    setViewTreeSavedStateRegistryOwner(this)
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    addView(composeView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
  }

  override val lifecycle: Lifecycle get() = registry
  override val viewModelStore: ViewModelStore get() = store
  override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

  fun setContent(content: @Composable () -> Unit) = composeView.setContent(content)

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (registry.currentState == Lifecycle.State.CREATED) registry.currentState = Lifecycle.State.STARTED
  }

  /** La sessione e' a schermo: gli effetti e i flussi legati al ciclo di vita ripartono. */
  fun resume() {
    if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.RESUMED
  }

  /** La sessione e' nascosta: tutto cio' che e' `collectAsStateWithLifecycle` si ferma da solo. */
  fun pause() {
    if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.CREATED
  }

  fun dispose() {
    if (registry.currentState == Lifecycle.State.DESTROYED) return
    registry.currentState = Lifecycle.State.DESTROYED
    store.clear()
  }
}
