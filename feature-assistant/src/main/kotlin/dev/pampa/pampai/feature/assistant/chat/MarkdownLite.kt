package dev.pampa.pampai.feature.assistant.chat

import dev.pampa.pampai.core.assistant.voice.PlainText

/**
 * Il Markdown ridotto a testo per le anteprime (cronologia, notifiche, condivisione in chiaro).
 * La versione vera vive nel core ([PlainText]), dove la usa anche la voce: qui c'e' solo il nome
 * che la UI conosce.
 */
object MarkdownLite {
  fun plainText(markdown: String): String = PlainText.of(markdown)
}
