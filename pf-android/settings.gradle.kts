// Bibliothèque Android commune de la Plateforme Commune (contrat API v0.1.2).
pluginManagement {
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
// Le module :pf-android (AAR) n'est inclus que si le SDK Android est présent (ANDROID_HOME ou ANDROID_SDK_ROOT).
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null) {
    include(":pf-android")
}
