// Module AAR. Plugins et versions : voir settings.gradle.kts (pluginManagement). Inclus seulement si le SDK Android est présent.
// Construction : voir docs/CONSTRUIRE_AAR.md  (./gradlew :pf-android:assembleRelease).
// Les tests de ce module (src/test) s'exécutent via :pf-android-check, pas ici.
plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}
android {
    namespace = "pf.android"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies { api(project(":pf-core")) }

afterEvaluate {
    publishing {
        publications { create<MavenPublication>("release") { artifactId = "pf-android"; from(components["release"]) } }
        repositories { maven(url = rootProject.layout.projectDirectory.dir("dist-maven")) }
    }
}
