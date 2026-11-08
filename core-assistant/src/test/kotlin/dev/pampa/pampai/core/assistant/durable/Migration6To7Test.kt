package dev.pampa.pampai.core.assistant.durable

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.pampai.core.assistant.db.PampaiDatabase
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Migration6To7Test {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val file=context.getDatabasePath("foundation-6-7.db").apply { parentFile?.mkdirs() }
  @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), file, AndroidSQLiteDriver(), PampaiDatabase::class)
  @Test fun everyLegacyTableAndValueSurvivesAdditiveMigration() {
    val schema=context.assets.open("dev.pampa.pampai.core.assistant.db.PampaiDatabase/6.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    val entities=schema.getValue("database").jsonObject.getValue("entities").jsonArray
    val expected=mutableMapOf<String, List<Pair<String, String>>>()
    helper.createDatabase(6).use { old ->
      for (entity in entities) {
        val table=entity.jsonObject.getValue("tableName").jsonPrimitive.content
        val values=entity.jsonObject.getValue("fields").jsonArray.map { field ->
          val f=field.jsonObject
          f.getValue("columnName").jsonPrimitive.content to if (f.getValue("affinity").jsonPrimitive.content=="INTEGER") "7" else "'synthetic-preserved'"
        }
        old.execSQL("INSERT INTO `$table` (${values.joinToString { "`${it.first}`" }}) VALUES (${values.joinToString { it.second }})")
        expected[table]=values
      }
    }
    helper.runMigrationsAndValidate(7, emptyList()).use { migrated ->
      for ((table, values) in expected) {
        migrated.prepare("SELECT ${values.joinToString { "`${it.first}`" }} FROM `$table`").use { row ->
          assertTrue("lost $table", row.step())
          for ((index, value) in values.withIndex()) {
            if (value.second=="7") assertEquals("$table.${value.first}", 7L, row.getLong(index))
            else assertEquals("$table.${value.first}", "synthetic-preserved", row.getText(index))
          }
          assertFalse(row.step())
        }
      }
      migrated.prepare("SELECT COUNT(*) FROM aria_areas").use { row -> assertTrue(row.step()); assertEquals(0L, row.getLong(0)) }
      migrated.prepare("SELECT COUNT(*) FROM aria_attempts").use { row -> assertTrue(row.step()); assertEquals(0L, row.getLong(0)) }
    }
  }
}
