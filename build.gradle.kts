plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

// Derleme çıktıları OneDrive'ın dışında dursun.
System.getenv("LOCALAPPDATA")?.let { local ->
    allprojects { layout.buildDirectory.set(file("$local/B12build/${project.name}")) }
}
