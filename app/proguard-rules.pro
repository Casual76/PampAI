# Le tracce devono restare leggibili sul telefono: si toglie il codice inutile, non i nomi.
-dontobfuscate

# kotlinx.serialization: i JSON dei provider si navigano a mano (JsonElement), ma il runtime
# usa la riflessione sui suoi serializer interni.
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# pdfbox-android cerca un decoder JPEG2000 opzionale (gemalto jp2) che non includiamo: senza questa riga R8 si ferma.
-dontwarn com.gemalto.jp2.**
