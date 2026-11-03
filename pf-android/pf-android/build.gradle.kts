// Module AAR — NON CONSTRUIT dans l'environnement de développement initial (SDK Android inaccessible).
// À construire sur un poste avec le SDK : `ANDROID_HOME=... ./gradlew :pf-android:assembleRelease`.
// Les mêmes sources sont compilées et testées sur JVM par :pf-android-check (voir docs/RAPPORT_TESTS.md).
plugins {
    id("com.android.library") version "8.5.2"
    kotlin("android") version "2.0.21"
}
android {
    namespace = "pf.android"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { api(project(":pf-core")) }
