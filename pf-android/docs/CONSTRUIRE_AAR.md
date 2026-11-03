# Construire l'AAR `pf-android` — procédure reproductible

Statut : **l'AAR n'a pas pu être construit dans l'environnement cloud initial** (voir « Blocage constaté »). Cette procédure est
prête pour un poste Android adapté ; son étape de construction (`assembleRelease`) est **NON VÉRIFIÉE** tant qu'elle n'a pas été exécutée là-bas.

## Versions (couple retenu, déclaré une seule fois dans `settings.gradle.kts`)
| Élément | Version | Justification |
|---|---|---|
| Gradle (wrapper `gradlew`) | **8.7**, somme SHA-256 de la distribution épinglée | minimum exigé par AGP 8.5.2 ; maximum officiellement pris en charge par Kotlin 2.0.21 : 8.8 |
| Android Gradle Plugin | **8.5.2** | compileSdk 34 |
| Kotlin | **2.0.21** | inchangé |
| JDK d'exécution | **17 ou plus** (21 validé ici) | cible bytecode Java 17 (`release 17`), aucune « toolchain » imposée |
| Android | compileSdk **34**, minSdk **26** | build-tools 34.0.0 |

Sommes de contrôle **vérifiées le 2026-11-03 sur la page officielle https://gradle.org/release-checksums/** (récupérée en HTTPS, comparée au contenu du dépôt ; aucune valeur mémorisée) :
- `gradle-8.7-bin.zip` (épinglé dans `gradle/wrapper/gradle-wrapper.properties`) : `544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d` — **identique** à la page officielle (8.7, « Binary-only ZIP Checksum ») et à l'archive téléchargée.
- `gradle/wrapper/gradle-wrapper.jar` : `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172` — **identique** au « Wrapper JAR Checksum » officiel de Gradle 8.14.3 (le jar a été généré par ce Gradle ; il lance n'importe quelle distribution, ici 8.7).
Pour recontrôler : `sha256sum gradle/wrapper/gradle-wrapper.jar` (Linux) ou `Get-FileHash gradle\wrapper\gradle-wrapper.jar -Algorithm SHA256` (Windows) et comparer à la page officielle.

## Prérequis à réunir sur le poste de construction
1. **JDK 17+** (Android Studio en embarque un).
2. **SDK Android** : `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`, licences acceptées (`sdkmanager --licenses`).
   Renseigner `ANDROID_HOME` (ou `sdk.dir=…` dans `pf-android/local.properties`, fichier ignoré par Git — ne jamais le commiter).
3. **Accès réseau** à `dl.google.com` (SDK + plugin AGP, hébergés sur Google Maven), `repo.maven.apache.org`, `plugins.gradle.org`, `services.gradle.org`.
4. Aucun secret n'est nécessaire (la construction ne signe rien ; la signature de l'APK finale appartient à chaque application).

## Construction

Linux / macOS :
```
cd pf-android
tools/construire-aar.sh          # vérifie les prérequis, construit, publie dans dist-maven/, contrôle l'AAR, affiche son SHA-256
```

Windows (PowerShell) — équivalent manuel, **NON VÉRIFIÉ** (aucun Windows dans l'environnement de préparation) :
```
cd pf-android
$env:ANDROID_HOME = "C:\Users\<vous>\AppData\Local\Android\Sdk"
.\gradlew.bat --no-daemon :pf-android:assembleRelease :pf-core:publish :pf-android:publish
Get-FileHash .\pf-android\build\outputs\aar\pf-android-release.aar -Algorithm SHA256
```

Sorties : `pf-android/build/outputs/aar/pf-android-release.aar` et le dépôt Maven local `dist-maven/` (ignoré par Git) contenant
**`pf-core-0.1.0.jar` + `pf-android-0.1.0.aar` + POM/module** : l'AAR **n'embarque pas** `pf-core` ; il le déclare comme dépendance.
Une application consomme les deux (ajouter `dist-maven` ou le dépôt Maven interne comme dépôt, puis
`implementation("pf:pf-android:0.1.0")` — `pf-core` et BouncyCastle suivent par transitivité), ou inclut les deux modules dans son build.

Contrôle automatique de l'AAR (`tools/construire-aar.sh --verifier <aar>`) : présence de `AndroidManifest.xml` et `classes.jar`, des 6 classes clés
(`AndroidSqlDb`, `AndroidKeystoreVault`, `PlatformRuntime`, `PfSyncJobService`, `SyncScheduler`, `NetworkSyncTrigger`), du service de tâche dans le manifeste.

## Contrôles à enchaîner sur le poste Android (dans l'ordre)
1. `./gradlew :pf-core:test` avec `PF_PLATFORM_DIR` = arbre de la plateforme v0.1.2 (Python 3 + `cryptography`) : 23 tests attendus réussis.
2. `./gradlew :pf-android:assembleRelease` : doit produire l'AAR (**première exécution réelle de ce module**).
3. `./gradlew :pf-android-check:test -Probolectric` après avoir retiré les exclusions `androidx.test` de `pf-android-check/build.gradle.kts` :
   exécute les tests Android sous Robolectric (jamais exécutés jusqu'ici).
4. Recette tablette : `docs/RAPPORT_TESTS.md`.

## Blocage constaté dans l'environnement cloud (précis)
- **Plugin Android Gradle 8.5.2 introuvable** : `Plugin [id: 'com.android.library', version: '8.5.2'] was not found` — le plugin est publié sur Google Maven
  (`dl.google.com`), refusé par la politique réseau de l'environnement (accès impossible, non contourné).
- **SDK Android absent** : pas d'`ANDROID_HOME`, pas de `sdkmanager`, pas de `platforms/android-34` ; son téléchargement passe aussi par `dl.google.com`.
- Maven Central renvoie des **429** intermittents (limitation de débit) : relancer la commande suffit.
- Aucun logiciel n'a été installé pour contourner ces limites.

## Ce qui est vérifié avec cette configuration (Gradle 8.7 par le wrapper, JDK 21)
- `./gradlew` télécharge et démarre Gradle 8.7 (somme de contrôle épinglée) ; **23/23 tests de `pf-core`** réussis ; `:pf-core:publish` produit jar, sources, POM et module ;
  sources Android et tests Android **compilent** contre le framework Android 15.
- Ce qui ne l'est pas : `:pf-android:assembleRelease`, la publication de l'AAR, la compatibilité réelle AGP 8.5.2 + Gradle 8.7 sur ce projet.
