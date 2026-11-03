// Compile les sources de :pf-android contre le framework Android réel (android-all de Robolectric)
// et exécute leurs tests sur JVM avec Robolectric. Ne produit pas d'AAR/APK.
plugins { kotlin("jvm") }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
// Cible Java 17 quel que soit le JDK (17 ou plus) qui exécute Gradle : pas de « toolchain » imposée.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile> { options.release.set(17) }

// androidx.test (Google Maven) est inaccessible depuis l'environnement de développement initial (dl.google.com bloqué) :
// Robolectric est exécuté sans ces artefacts optionnels. Sur un poste avec accès normal, retirer ces exclusions.
configurations.matching { it.name.startsWith("test") }.configureEach {
    exclude(group = "androidx.test")
    exclude(group = "androidx.test.espresso")
    exclude(group = "androidx.test.ext")
}

val androidAll: Configuration by configurations.creating
val androidAllCoordinate = "org.robolectric:android-all:15-robolectric-13954326" // Android 15 (SDK 35)

sourceSets {
    main { kotlin.srcDir("../pf-android/src/main/kotlin") }
    // Aides de test partagées avec :pf-core (serveur v0.1.2 réel, pannes réseau, JDBC) — pas les tests de pf-core eux-mêmes.
    test { kotlin { srcDir("../pf-core/src/test/kotlin"); exclude("**/SyncIntegrationTest.kt"); srcDir("../pf-android/src/test/kotlin") } }
}

dependencies {
    api(project(":pf-core"))
    compileOnly(androidAllCoordinate)
    androidAll("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") // variante exigée par Robolectric 4.14.1
    testImplementation(project(":pf-core"))
    testImplementation(androidAllCoordinate)
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    // Désactivé par défaut : Robolectric exige androidx.test (Google Maven), inaccessible dans l'environnement de
    // développement initial. Sur un poste normal : `./gradlew :pf-android-check:test -Probolectric` (et retirer les exclusions ci-dessus).
    // `compileTestKotlin` vérifie néanmoins que les tests compilent contre le vrai framework Android.
    enabled = project.hasProperty("robolectric")
    useJUnit()
    environment("PF_PLATFORM_DIR", System.getenv("PF_PLATFORM_DIR") ?: "")
    // Mode hors ligne : le jar Android est résolu par Gradle, Robolectric ne télécharge rien.
    doFirst {
        val dir = layout.buildDirectory.dir("android-all").get().asFile.apply { mkdirs() }
        androidAll.resolve().forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        systemProperty("robolectric.offline", "true")
        systemProperty("robolectric.dependency.dir", dir.absolutePath)
    }
    testLogging { events("passed", "skipped", "failed") }
}
