# Rapport de tests — bibliothèque Android commune (contrat v0.1.2)

Date : 2026-11-03. Environnement : conteneur cloud Linux, JDK 21, Gradle 8.14.3, Kotlin 2.0.21, Python 3.13.
Données : **entièrement fictives** (application `essai`, entités `commande`/`note`/`photo`, certificats et clés de TEST générés à la volée
et détruits). Aucun secret réel. Serveur .20, N:, plateforme v0.1.2 et les sept applications : **non touchés**.
Preuve de non-modification : sources `server/src` et `contracts` **identiques** à l'archive officielle `plateforme-commune-0.1.2.zip` (diff vide).

## VÉRIFIÉ (exécuté ici)

**23 tests d'intégration sur `pf-core`, 23 réussis, 0 échec, 0 ignoré, 3 exécutions consécutives identiques.**
Chaque test lance le **vrai serveur Python v0.1.2 non modifié** en HTTPS local (certificat de test, SAN 127.0.0.1) et pilote le client Kotlin
sur une vraie base SQLite fichier (JDBC en test).

| Exigence | Tests vérifiés |
|---|---|
| Connexion HTTPS sécurisée | Autorité inconnue → rejet `TLS` ; certificat valide mais nom d'hôte non conforme → rejet TLS ; URL `http://` refusée à la construction ; toutes les autres suites s'exécutent en TLS avec CA épinglée. Jeton 15 min renouvelé : 401 forgé → **une seule** nouvelle signature puis succès ; horloge tablette décalée d'1 h → connexion toujours possible (décalage appris via `/v1/health`). |
| Enrôlement | Code à usage unique (réutilisation → 403 `FORBIDDEN`) ; la graine n'est jamais en clair en base (enveloppe AES-GCM) ; jeton d'accès non persisté ; coffre réinitialisé → erreur explicite `DEVICE_KEY_UNUSABLE`, données conservées. |
| Stockage local persistant | Redémarrage simulé : appareil toujours enrôlé, données et file intactes ; état `SENDING` d'un arrêt brutal repris en `PENDING`. |
| File hors ligne | Réseau coupé : 3 opérations conservées, état `OFFLINE`, serveur à 0 opération ; retour réseau → 3 acquittées ; resynchronisations répétées **n'ajoutent rien** (compteur serveur inchangé). |
| Synchronisation idempotente | **Réponse perdue après application côté serveur** : le renvoi donne `DUPLICATE`, 1 seule opération et version 1 côté serveur ; 120 opérations envoyées en ≥ 3 lots sans perte ; 4 threads × 10 écritures pendant la synchronisation automatique → 40 acquittées, 40 côté serveur ; numéro officiel `CMD-AAAA-000001` attribué par le serveur et restitué ; second appareil relit les données ; suppression propagée. |
| Conflits / permissions / révocation | Conflit conservé, signalé (`ATTENTION`), résolu par l'utilisateur, fusion correcte côté serveur ; opération interdite → `REJECTED`/`FORBIDDEN` conservée ; appareil révoqué → `REVOKED`, plus aucune requête réseau, opération conservée ; indication hors ligne limitée à 12 h. |
| Photos / documents avec reprise | Fichier de 1 Mio + 777 octets en 17 morceaux : coupure après 5 → reprise, **12 morceaux envoyés, chacun exactement une fois (5+12 = 17)**, SHA-256 identique côté serveur, opération non envoyée avant fichier complet ; reprise **après redémarrage de l'application** (5 morceaux manquants sur 7) ; fichier local altéré → **jamais transmis**, état `ATTENTION` ; fichier absent côté serveur alors que le client le croit transféré → retransmis puis acquitté. |
| États consultables par l'interface | Écouteur : transitions `PENDING`, `OFFLINE`, `SYNCING`, `OK` observées ; progression octets par fichier ; compteurs ; dernière erreur ; entrées invalides (nom d'entité, id, doublon local, charge > 256 Kio) refusées. |

**Compilation contre le vrai framework Android 15** (`android-all` Robolectric, SDK 35) : les sources de `pf-android` **et** le sample
d'intégration compilent sans erreur (`:pf-android-check:compileKotlin`), ainsi que les tests Android (`compileTestKotlin`).

## NON VÉRIFIÉ (non exécuté ici — à ne pas considérer comme testé)

1. **Aucun AAR ni APK n'a été construit** : le SDK Android et le plugin Android Gradle viennent de `dl.google.com`, bloqué par la politique de
   l'environnement (403, non contourné). Le module `:pf-android` (AAR) est fourni mais **jamais construit**.
2. **Les tests Android (Robolectric) ne s'exécutent pas ici** : Robolectric exige `androidx.test` (Google Maven, bloqué). Ils sont écrits et
   compilent, mais ne sont **pas exécutés** : `AndroidSqlDb` (types, rollback, imbrication, persistance), `PlatformRuntime`, planification
   `JobScheduler`, `PfSyncJobService`, `NetworkSyncTrigger`, écouteur sur thread principal. À lancer sur un poste normal :
   `./gradlew :pf-android-check:test -Probolectric` (retirer les exclusions `androidx.test` du fichier de build).
   La logique métier qu'ils exercent est, elle, vérifiée sur JVM avec JDBC ; ce qui reste non vérifié est l'**adaptateur SQLite Android**.
3. **`AndroidKeystoreVault`** : compile, jamais exécuté (Robolectric n'a pas de fournisseur `AndroidKeyStore` ; seule une tablette le valide).
4. **Ed25519 via BouncyCastle sur Android** : vérifié sur JVM uniquement. Risque connu : le BouncyCastle réduit du système Android peut
   masquer la version embarquée (même nom de paquet). À confirmer sur tablette (voir liste ci-dessous) ; solution de repli : relocaliser le paquet
   (jarjar/shadow) ou utiliser l'Ed25519 natif d'Android 13+.
5. `HttpsTransport` sur la pile HTTP/TLS d'Android (Conscrypt/OkHttp interne) : vérifié sur la pile JDK uniquement, notamment la vérification du
   nom d'hôte et l'épinglage de CA. Le test de nom d'hôte non conforme est l'essai le plus important à refaire sur la tablette.
6. Pas de test avec le serveur **.20 réel** ni avec un certificat émis par la vraie autorité de la plateforme ; pas de test réseau Wi-Fi réel,
   coupures radio, veille/Doze, économiseur de batterie, ni gros volumes (> quelques Mio).
7. Modèle de menaces : pas d'audit externe ; l'épinglage se fait par autorité interne (CA), pas par empreinte de clé du serveur.

## À valider sur tablette physique (liste de recette)

1. Installation d'une APK d'essai intégrant la bibliothèque ; enrôlement avec un code de test.
2. **Rejet TLS** : pointer l'application sur le serveur avec une autre CA / un autre nom → état `lastError = TLS`, aucune donnée envoyée.
3. Mode avion : saisir 5 commandes, redémarrer la tablette, rouvrir : les 5 sont là ; couper le mode avion → synchronisées automatiquement, 1 fois chacune.
4. Fermer l'application (balayage) avec des opérations en attente, rétablir le réseau : `JobScheduler` les envoie sans ouvrir l'application.
5. Photo de plusieurs Mio : couper le Wi-Fi à mi-chemin, reprendre : seuls les morceaux manquants repartent ; empreinte identique côté serveur.
6. Écran verrouillé / Doze / économiseur : la synchronisation périodique (15 min) s'exécute-t-elle ?
7. Révocation de l'appareil côté serveur : état `REVOKED` visible, plus aucune requête.
8. Ed25519/BouncyCastle : l'enrôlement et l'obtention de jeton réussissent (voir risque 4) ; pas de `NoSuchMethodError`/`ClassNotFound`.
9. Keystore : après un « effacer les données de l'application », l'enrôlement est redemandé proprement ; sauvegarde/restauration désactivée.
10. Horloge de la tablette volontairement fausse (± 1 h) : connexion toujours possible.
11. Mesures : consommation batterie, temps de synchronisation de 100 opérations, taille de l'APK ajoutée par BouncyCastle.

## Limites connues de conception
- Les décimaux JSON passent par `Double` (`org.json`) : stocker les montants en centimes entiers ou en chaînes.
- Si la réponse d'enrôlement est perdue, le code (à usage unique) est consommé : nouvel enrôlement avec un nouveau code (appareil orphelin côté serveur à révoquer).
- Un appareil ré-enrôlé après révocation obtient un nouvel identifiant : les opérations déjà reçues sous l'ancien seraient rejetées si renvoyées.
- Une opération dont un fichier est en échec local définitif reste bloquée (visible en `ATTENTION`) jusqu'à `retryFile` ou décision de l'utilisateur.
