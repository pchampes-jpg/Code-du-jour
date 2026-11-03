// Aucun plugin appliqué à la racine : chaque module charge les siens (versions dans settings.gradle.kts).
// Déclarer Kotlin ici le chargerait dans le parent, hors de portée du plugin Android du module :pf-android.
allprojects {
    group = "pf"
    version = "0.1.0"
}
