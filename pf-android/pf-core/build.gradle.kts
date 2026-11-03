plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
// Cible Java 17 quel que soit le JDK (17 ou plus) qui exécute Gradle : pas de « toolchain » imposée.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<JavaCompile> { options.release.set(17) }

dependencies {
    // Signature Ed25519 (API légère BouncyCastle, indépendante des fournisseurs JCA de l'appareil).
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // org.json est fourni par Android ; ajouté seulement pour compiler et tester sur JVM.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3") // base SQLite des tests JVM uniquement
}

tasks.test {
    useJUnitPlatform()
    // Les tests d'intégration lancent le serveur Python v0.1.2 NON MODIFIÉ (chemin fourni par PF_PLATFORM_DIR).
    environment("PF_PLATFORM_DIR", System.getenv("PF_PLATFORM_DIR") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false }
}

java { withSourcesJar() }
publishing {
    publications { create<MavenPublication>("core") { artifactId = "pf-core"; from(components["java"]) } }
    repositories { maven(url = rootProject.layout.projectDirectory.dir("dist-maven")) }
}
