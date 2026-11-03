# Bibliothèque Android commune — guide d'intégration

Contrat utilisé : **Plateforme Commune API v0.1.2**, sans aucune modification du serveur (voir `RAPPORT_TESTS.md`).
Langage : Kotlin. Dépendances : BouncyCastle (Ed25519, API légère) ; `org.json` fourni par Android. Pas de WorkManager,
pas de Room, pas d'OkHttp/Retrofit, pas d'injection de dépendances.

## Architecture (volontairement simple)

```
pf-core            Kotlin pur, testé sur JVM contre le vrai serveur
  PlatformClient     file d'opérations hors ligne, vue locale, synchronisation, fichiers, états
  SyncCoordinator    synchronisation automatique (regroupe les demandes, un seul thread)
  HttpsTransport     HTTPS (HttpsURLConnection), CA interne épinglée, nom d'hôte vérifié, TLS ≥ 1.2
  LocalStore         schéma SQLite + migrations ; accès via l'interface SqlDb
  DeviceKey/SecretVault   Ed25519 + enveloppe de la graine
pf-android         fine couche Android (≈ 300 lignes)
  AndroidSqlDb       SQLiteDatabase (WAL, synchronous=FULL)
  AndroidKeystoreVault   clé AES-GCM non exportable d'Android Keystore (StrongBox si présent)
  NetworkSyncTrigger / SyncScheduler / PfSyncJobService   déclencheurs réseau et JobScheduler (arrière-plan, persistant)
  PlatformRuntime    assemblage standard, une ligne dans Application.onCreate()
```

Ce que la bibliothèque **ne refait pas** côté serveur : numérotation officielle, contrôle des permissions, détection des
conflits, idempotence `(app_id, op_id)`, publication atomique des fichiers. Elle les **consomme** et les restitue à l'interface.

## Intégrer dans une APK

1. `settings.gradle.kts` : inclure `:pf-core` et `:pf-android` (ou publier l'AAR dans le dépôt Maven interne).
   `minSdk 26` (Android 8.0), Java 17.
2. Embarquer l'autorité de certification **publique** du serveur dans `assets/pf_ca.pem` (ce n'est pas un secret ;
   la clé privée du serveur ne quitte jamais le serveur). Sans `caPem`, ce sont les autorités du système qui s'appliquent.
3. Manifest de l'application : `android:allowBackup="false"` (la clé Keystore n'est pas sauvegardable : une base restaurée
   sur un autre appareil exigerait un nouvel enrôlement) et `android:name=".ExempleApplication"`.
   Le manifeste de la bibliothèque déclare déjà INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED et le service de tâche.
4. `Application.onCreate()` : `PlatformRuntime.install(this, PlatformOptions(appId, baseUrl, caPem))` — voir
   `sample/src/exemple/ExempleIntegration.kt`.
5. Écrans : `client.create/update/delete`, `client.list/get`, `client.addFile`, `client.status()` et
   `client.addMainThreadStatusListener { … }`.

## Enrôlement
L'administrateur émet un code à usage unique (`create-enrollment`). L'utilisateur le saisit une fois ; la clé Ed25519 de
l'appareil est créée sur la tablette, **enveloppée par le Keystore**, et seule la clé publique part au serveur. Le code est un
secret : ne jamais le journaliser ni le conserver. Si la réponse est perdue après consommation, demander un nouveau code.

## Fonctions et garanties

| Besoin | Comportement |
|---|---|
| Connexion HTTPS sécurisée | https obligatoire ; certificat **toujours** vérifié (CA interne épinglée ou système) ; nom d'hôte vérifié ; aucune option pour désactiver. Rejet TLS distingué d'une panne réseau (`lastError = "TLS"` / `"NETWORK"`). |
| Stockage local persistant | SQLite WAL, `synchronous=FULL` : une saisie acceptée survit à une coupure de courant ; migrations automatiques ; graine de signature jamais en clair. |
| File hors ligne | Chaque écriture est une transaction durable (`PENDING`). Jamais supprimée automatiquement. Rejets et conflits conservés jusqu'à décision utilisateur. |
| Synchronisation idempotente | Identifiant unique par opération, renvoi sûr après panne/réponse perdue (`DUPLICATE`), ordre préservé par entité, lots ≤ 50 opérations et < 800 Kio, délais de réessai exponentiels avec gigue. |
| Photos / documents | Copie en flux (jamais en mémoire entière), SHA-256, morceaux de 256 Kio vérifiés un à un, **reprise** après coupure ou redémarrage (seuls les morceaux manquants sont envoyés), contrôle d'intégrité local avant envoi, publication atomique côté serveur. |
| États pour l'interface | `status()` + écouteur : `SyncLevel` {NOT_ENROLLED, REVOKED, ATTENTION, SYNCING, OFFLINE, PENDING, UNKNOWN, OK}, compteurs, progression par fichier, dernière erreur, curseur, date de dernière synchro. |

## Règles d'usage à respecter dans les applications
- **Montants** : ne pas stocker de décimaux flottants dans les charges utiles (`org.json` convertit en `Double`) ; utiliser des
  entiers (centimes) ou des chaînes.
- Une charge utile > 256 Kio est refusée localement : utiliser `addFile`.
- `canOffline(permission)` n'est qu'une **indication** d'interface (valable 12 h après la dernière connexion) ; le serveur applique
  les permissions à chaque synchronisation.
- Appareil révoqué (`REVOKED`) : plus aucune requête n'est émise ; données conservées ; ré-enrôler après décision de l'administrateur.
- `DEVICE_KEY_UNUSABLE` : coffre réinitialisé ou base restaurée ailleurs → ré-enrôlement ; données locales conservées.
- Les fichiers locaux ne sont jamais supprimés automatiquement ; `purgeUploadedFiles()` libère l'espace des fichiers entièrement
  transférés et acquittés, à la demande.
