// Telefon ve saat uygulaması AYNI anahtarla imzalanmalı, yoksa birbirini görmez (Wearable Data Layer).
import java.util.Properties
val p = Properties().apply { rootDir.resolve("keystore/keystore.properties").inputStream().use { load(it) } }
extra["b12Store"] = rootDir.resolve("keystore/" + p.getProperty("storeFile"))
extra["b12Pass"] = p.getProperty("storePassword")
extra["b12Alias"] = p.getProperty("keyAlias")
