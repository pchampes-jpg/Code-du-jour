// Bibliothèque Android commune de la Plateforme Commune (contrat API v0.1.2).
pluginManagement {
    // Versions des plugins déclarées ICI, une seule fois. Couple validé par la documentation officielle :
    // Gradle 8.7 (wrapper) + AGP 8.5.2 (minimum Gradle 8.7) + Kotlin 2.0.21 (compatible jusqu'à Gradle 8.8).
    plugins {
        kotlin("jvm") version "2.0.21"
        kotlin("android") version "2.0.21"
        id("com.android.library") version "8.5.2"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google() // requis seulement pour construire le module pf-android (AAR) sur un poste équipé du SDK Android
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "pf-android-lib"
include(":pf-core")          // Kotlin pur : stockage, file d'opérations, synchronisation, fichiers, HTTPS (testé sur JVM)
include(":pf-android-check") // JVM : compile les sources Android contre android-all et exécute leurs tests (Robolectric)
// Le module :pf-android (AAR) n'est inclus que si le SDK Android est présent
// (ANDROID_HOME, ANDROID_SDK_ROOT ou sdk.dir dans local.properties).
val sdkPresent = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").takeIf { it.exists() }?.readLines()?.any { it.trim().startsWith("sdk.dir=") } == true
if (sdkPresent) {
    include(":pf-android")
}
