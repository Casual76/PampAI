package dev.pampa.pampai.core.assistant.receipts

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import dev.antigravity.fluidengine.ai.tools.ToolOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarReceiptsTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private class Source(val title: String? = "Saved", val unavailable: Boolean = false) : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
      if (unavailable) return null
      val columns = projection!!.map { it }.toTypedArray()
      return MatrixCursor(columns).apply {
        if (title != null) addRow(columns.map { when (it) { CalendarContract.Events.TITLE -> title; CalendarContract.Events.DELETED -> 0; else -> "100" } })
      }
    }
  }
  private fun source(provider: Source) { ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider) }

  @Test fun `matching entity readback proves completed and mismatch stays uncertain`() = runBlocking {
    source(Source())
    val expected = ContentValues().apply { put(CalendarContract.Events.TITLE, "Saved") }
    val verified = CalendarReceipts.verify(context, 42, expected).result!!
    assertEquals(ToolOutcome.COMPLETED, verified.outcome)
    assertEquals("42", verified.entities.single().id)
    assertNotNull(verified.freshness!!.observedAtMillis)
    expected.put(CalendarContract.Events.TITLE, "Different")
    assertEquals(ToolOutcome.UNCERTAIN, CalendarReceipts.verify(context, 42, expected).result!!.outcome)
  }
  @Test fun `null cursor cannot certify deletion but actual absence can`() = runBlocking {
    source(Source(unavailable = true))
    assertEquals(ToolOutcome.UNCERTAIN, CalendarReceipts.verify(context, 42, deleted = true).result!!.outcome)
    source(Source(title = null))
    assertEquals(ToolOutcome.COMPLETED, CalendarReceipts.verify(context, 42, deleted = true).result!!.outcome)
    assertEquals(ToolOutcome.UNCERTAIN, CalendarReceipts.verify(context, 42).result!!.outcome)
  }
}
