# Le tracce devono restare leggibili sul telefono: si toglie il codice inutile, non i nomi.
-dontobfuscate

# kotlinx.serialization: i JSON dei provider si navigano a mano (JsonElement), ma il runtime
# usa la riflessione sui suoi serializer interni.
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
