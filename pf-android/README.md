# pf-android — bibliothèque Android commune de la Plateforme Commune (API v0.1.2)

- `pf-core/` : cœur Kotlin pur (stockage, file hors ligne, synchronisation idempotente, fichiers avec reprise, HTTPS, états). Testé sur JVM contre le vrai serveur v0.1.2.
- `pf-android/` : fine couche Android (SQLite, Keystore, JobScheduler, réseau). Compilée contre le framework Android 15 ; AAR non construit ici.
- `pf-android-check/` : compilation contre `android-all` + tests Robolectric (non exécutables dans l'environnement initial).
- `sample/` : intégration minimale pour une future APK. `tools/serveur_essai.py` : banc d'essai (serveur v0.1.2 réel, données fictives).
- `docs/CONSTRUIRE_AAR.md` (procédure de construction de l'AAR), `docs/GUIDE_INTEGRATION.md`, `docs/RAPPORT_TESTS.md` (ce qui est testé / non testé / à valider sur tablette).

Lancer les tests du cœur : `PF_PLATFORM_DIR=<arbre plateforme v0.1.2> gradle :pf-core:test` (Python 3 + `cryptography` requis ; le serveur est lu, jamais modifié).
