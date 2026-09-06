package dev.pampa.pampai.core.assistant.tools.device

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import dev.antigravity.fluidengine.ai.tools.AiTool
import dev.antigravity.fluidengine.ai.tools.AiToolGroup
import dev.antigravity.fluidengine.ai.tools.Args.str
import dev.antigravity.fluidengine.ai.tools.ConfirmationText
import dev.antigravity.fluidengine.ai.tools.Schema
import dev.antigravity.fluidengine.ai.tools.ToolOutput
import dev.antigravity.fluidengine.ai.tools.ToolText
import dev.pampa.pampai.core.assistant.tools.PampaiGroup
import dev.pampa.pampai.core.assistant.tools.PampaiToolContext
import dev.pampa.pampai.core.assistant.tools.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal data class Contact(val id: Long, val name: String, val phones: List<Pair<String, String>>, val emails: List<String>)

internal object Contacts {
  suspend fun search(ctx: PampaiToolContext, query: String, limit: Int = 8): List<Contact> = withContext(Dispatchers.IO) {
    val resolver = ctx.app.contentResolver
    val ids = mutableListOf<Pair<Long, String>>()
    resolver.query(
      ContactsContract.Contacts.CONTENT_URI,
      arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
      "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
      arrayOf("%${query.split(" ").first()}%"),
      "${ContactsContract.Contacts.TIMES_CONTACTED} DESC",
    )?.use { c ->
      while (c.moveToNext()) {
        val name = c.getString(1) ?: continue
        if (Text.matches(query, name)) ids += c.getLong(0) to name
      }
    }
    ids.sortedByDescending { Text.score(query, it.second) }.take(limit).map { (id, name) -> details(ctx, id, name) }
  }

  suspend fun details(ctx: PampaiToolContext, id: Long, name: String): Contact = withContext(Dispatchers.IO) {
    val resolver = ctx.app.contentResolver
    val phones = mutableListOf<Pair<String, String>>()
    resolver.query(
      ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
      "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
      arrayOf(id.toString()),
      null,
    )?.use { c ->
      while (c.moveToNext()) {
        val number = c.getString(0) ?: continue
        val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.app.resources, c.getInt(1), c.getString(2)).toString()
        phones += number to type
      }
    }
    val emails = mutableListOf<String>()
    resolver.query(
      ContactsContract.CommonDataKinds.Email.CONTENT_URI,
      arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
      "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
      arrayOf(id.toString()),
      null,
    )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { emails += it } }
    Contact(id, name, phones.distinctBy { it.first.filter { ch -> ch.isDigit() || ch == '+' } }, emails.distinct())
  }

  /** Un numero: dato direttamente, o cercato per nome. Torna anche il messaggio d'errore se ambiguo. */
  suspend fun resolveNumber(ctx: PampaiToolContext, contact: String?, number: String?): Pair<String?, String?> {
    number?.takeIf { it.any { ch -> ch.isDigit() } }?.let { return it to null }
    val query = contact ?: return null to "serve un contatto o un numero"
    val found = search(ctx, query, limit = 5)
    if (found.isEmpty()) return null to "nessun contatto che somigli a \"$query\""
    val exact = found.filter { Text.normalize(it.name) == Text.normalize(query) }
    val chosen = when {
      exact.size == 1 -> exact.first()
      found.size == 1 -> found.first()
      else -> return null to "piu' contatti possibili: ${found.joinToString(", ") { it.name }}. Chiedi all'utente quale"
    }
    val phone = chosen.phones.firstOrNull()?.first ?: return null to "${chosen.name} non ha un numero"
    return phone to chosen.name
  }
}

class ContattoCercaTool : AiTool<PampaiToolContext> {
  override val name = "contatto_cerca"
  override val group: AiToolGroup = PampaiGroup.CONTATTI
  override val description = "Cerca nei contatti del telefono per nome e da' numeri ed email. Per \"il numero di Marco\", \"che email ha la nonna?\"."
  override val parameters = Schema.obj(mapOf("nome" to Schema.str("il nome, anche parziale")), required = listOf("nome"))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    Device.permission(ctx, "leggere i contatti", Manifest.permission.READ_CONTACTS)?.let { return it }
    val query = args.str("nome") ?: return ToolOutput.error("manca il nome")
    val found = Contacts.search(ctx, query)
    if (found.isEmpty()) return ToolText.output { line("contatti", "nessuno che somigli a \"$query\"") }
    return ToolText.output {
      line("contatti trovati", found.size)
      found.forEach { c ->
        line("#${c.id} ${c.name}" + (c.phones.firstOrNull()?.let { " · ${it.first} (${it.second})" } ?: " · nessun numero") + (c.emails.firstOrNull()?.let { " · $it" } ?: ""))
      }
    }
  }
}

class ContattoDettaglioTool : AiTool<PampaiToolContext> {
  override val name = "contatto_dettaglio"
  override val group: AiToolGroup = PampaiGroup.CONTATTI
  override val description = "Tutti i numeri e le email di un contatto (per id da contatto_cerca, o per nome esatto)."
  override val parameters = Schema.obj(mapOf("id" to Schema.int("l'id del contatto"), "nome" to Schema.str("oppure il nome")))

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    Device.permission(ctx, "leggere i contatti", Manifest.permission.READ_CONTACTS)?.let { return it }
    val contact = dev.antigravity.fluidengine.ai.tools.Args.run { args.int("id") }?.let { id ->
      val name = withContext(Dispatchers.IO) {
        ctx.app.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), "${ContactsContract.Contacts._ID} = ?", arrayOf(id.toString()), null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
      } ?: return ToolOutput.error("contatto #$id non trovato")
      Contacts.details(ctx, id.toLong(), name)
    } ?: args.str("nome")?.let { Contacts.search(ctx, it, limit = 1).firstOrNull() } ?: return ToolOutput.error("contatto non trovato")
    return ToolText.output {
      line("contatto", contact.name)
      contact.phones.forEach { line("telefono", "${it.first} (${it.second})") }
      contact.emails.forEach { line("email", it) }
      if (contact.phones.isEmpty() && contact.emails.isEmpty()) line("nota", "nessun numero ne' email")
    }
  }
}

class ChiamaTool : AiTool<PampaiToolContext> {
  override val name = "chiama"
  override val group: AiToolGroup = PampaiGroup.CONTATTI
  override val description = "Fa partire una telefonata a un contatto o a un numero. Chiede sempre conferma. Solo se l'utente ha detto esplicitamente di chiamare."
  override val parameters = Schema.obj(mapOf("contatto" to Schema.str("il nome del contatto"), "numero" to Schema.str("oppure il numero")))
  override val isAction = true
  override val needsConfirmation = true

  override suspend fun describe(args: JsonObject, ctx: PampaiToolContext): ConfirmationText? {
    val (number, who) = Contacts.resolveNumber(ctx, args.str("contatto"), args.str("numero"))
    number ?: return null
    return ConfirmationText("Chiamare ${who ?: number}?", if (who != null) number else null)
  }

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    if (args.str("contatto") != null) Device.permission(ctx, "leggere i contatti", Manifest.permission.READ_CONTACTS)?.let { return it }
    val (number, whoOrError) = Contacts.resolveNumber(ctx, args.str("contatto"), args.str("numero"))
    number ?: return ToolOutput.error(whoOrError ?: "numero non trovato")
    Device.permission(ctx, "telefonare", Manifest.permission.CALL_PHONE)?.let { return it }
    ctx.confirm(name, "Chiamare ${whoOrError ?: number}?", if (whoOrError != null) number else null)?.let { return it }
    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
    if (!Device.launch(ctx, intent)) return ToolOutput.error("nessuna app telefono")
    return ToolText.output { line("fatto", "chiamata a ${whoOrError ?: number} in corso") }
  }
}

class MessaggioPreparaTool : AiTool<PampaiToolContext> {
  override val name = "messaggio_prepara"
  override val group: AiToolGroup = PampaiGroup.CONTATTI
  override val description = "Apre l'app di messaggi (SMS, WhatsApp o Telegram) con il destinatario e il testo gia' scritti: l'utente rilegge e preme invia. Non invia da solo."
  override val parameters = Schema.obj(
    mapOf(
      "contatto" to Schema.str("il nome del contatto"),
      "numero" to Schema.str("oppure il numero"),
      "testo" to Schema.str("il messaggio"),
      "canale" to Schema.str("con quale app (default sms)", enum = listOf("sms", "whatsapp", "telegram")),
    ),
    required = listOf("testo"),
  )
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    if (args.str("contatto") != null) Device.permission(ctx, "leggere i contatti", Manifest.permission.READ_CONTACTS)?.let { return it }
    val text = args.str("testo") ?: return ToolOutput.error("manca il testo")
    val channel = args.str("canale") ?: "sms"
    val (number, whoOrError) = Contacts.resolveNumber(ctx, args.str("contatto"), args.str("numero"))
    if (number == null && channel != "telegram") return ToolOutput.error(whoOrError ?: "serve un contatto o un numero")
    val digits = number?.filter { it.isDigit() || it == '+' }.orEmpty()
    val intent = when (channel) {
      "whatsapp" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${digits.trimStart('+')}?text=${Uri.encode(text)}")).setPackage("com.whatsapp")
      "telegram" -> Intent(Intent.ACTION_VIEW, Uri.parse("tg://msg?text=${Uri.encode(text)}" + (digits.takeIf { it.isNotEmpty() }?.let { "&to=$it" } ?: "")))
      else -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).putExtra("sms_body", text)
    }
    if (!Device.launch(ctx, intent)) return ToolOutput.error("l'app per $channel non e' installata o non risponde")
    return ToolText.output {
      line("fatto", "messaggio pronto in $channel per ${whoOrError ?: number ?: "il destinatario"}: l'utente lo invia lui")
      line("testo", text)
    }
  }
}

class EmailPreparaTool : AiTool<PampaiToolContext> {
  override val name = "email_prepara"
  override val group: AiToolGroup = PampaiGroup.CONTATTI
  override val description = "Apre l'app di posta con destinatario, oggetto e testo gia' scritti: l'utente rilegge e invia. Non invia da solo."
  override val parameters = Schema.obj(
    mapOf("a" to Schema.str("l'indirizzo, o il nome di un contatto"), "oggetto" to Schema.str("l'oggetto"), "testo" to Schema.str("il corpo")),
    required = listOf("testo"),
  )
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val text = args.str("testo") ?: return ToolOutput.error("manca il testo")
    var to = args.str("a")
    if (to != null && !to.contains("@")) {
      Device.permission(ctx, "leggere i contatti", Manifest.permission.READ_CONTACTS)?.let { return it }
      val contact = Contacts.search(ctx, to, limit = 1).firstOrNull() ?: return ToolOutput.error("contatto \"$to\" non trovato")
      to = contact.emails.firstOrNull() ?: return ToolOutput.error("${contact.name} non ha un'email")
    }
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${to?.let { Uri.encode(it) } ?: ""}"))
      .putExtra(Intent.EXTRA_SUBJECT, args.str("oggetto") ?: "")
      .putExtra(Intent.EXTRA_TEXT, text)
    if (!Device.launch(ctx, intent)) return ToolOutput.error("nessuna app di posta")
    return ToolText.output { line("fatto", "email pronta${to?.let { " per $it" } ?: ""}: l'utente la invia lui") }
  }
}

class CondividiTool : AiTool<PampaiToolContext> {
  override val name = "condividi"
  override val group: AiToolGroup = PampaiGroup.CONTATTI
  override val description = "Apre il menu di condivisione di Android con un testo (una risposta, un link, un riassunto): l'utente sceglie l'app."
  override val parameters = Schema.obj(mapOf("testo" to Schema.str("il testo da condividere"), "titolo" to Schema.str("un titolo (facoltativo)")), required = listOf("testo"))
  override val isAction = true

  override suspend fun run(args: JsonObject, ctx: PampaiToolContext): ToolOutput {
    val text = args.str("testo") ?: return ToolOutput.error("manca il testo")
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    args.str("titolo")?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
    val chooser = Intent.createChooser(send, "Condividi").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.app.startActivity(chooser) }.getOrElse { return ToolOutput.error("impossibile aprire la condivisione") }
    return ToolText.output { line("fatto", "menu di condivisione aperto") }
  }
}

fun contactTools(): List<AiTool<PampaiToolContext>> = listOf(ContattoCercaTool(), ContattoDettaglioTool(), ChiamaTool(), MessaggioPreparaTool(), EmailPreparaTool(), CondividiTool())
