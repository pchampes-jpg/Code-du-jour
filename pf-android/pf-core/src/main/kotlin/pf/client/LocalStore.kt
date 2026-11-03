package pf.client

/**
 * Stockage local persistant : file d'opérations transactionnelle + réplique confirmée par le serveur.
 * Même schéma logique que le client de référence Python, plus des colonnes de reprise de fichiers.
 */
internal class LocalStore(val db: SqlDb) {
    init { migrate() }

    private fun migrate() {
        db.transaction {
            val current = db.userVersion()
            for (i in current until MIGRATIONS.size) {
                for (statement in MIGRATIONS[i]) db.execute(statement)
                db.setUserVersion(i + 1)
            }
        }
    }

    fun getMeta(key: String): String? = db.queryOne("SELECT v FROM meta WHERE k=?", key)?.str("v")

    fun setMeta(key: String, value: Any?) {
        db.execute("INSERT OR REPLACE INTO meta(k, v) VALUES(?,?)", key, value?.toString())
    }

    companion object {
        val PENDING_STATES = listOf("PENDING", "SENDING", "WAITING_FILE")
        val MIGRATIONS: List<List<String>> = listOf(
            listOf(
                "CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)",
                """CREATE TABLE outbox(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, op_id TEXT UNIQUE NOT NULL, state TEXT NOT NULL,
                    type TEXT NOT NULL, entity TEXT NOT NULL, entity_id TEXT NOT NULL, base_version INTEGER NOT NULL,
                    schema_version INTEGER NOT NULL, created_at_device TEXT NOT NULL, payload_json TEXT NOT NULL,
                    numbering_json TEXT, files_json TEXT, attempts INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at INTEGER NOT NULL DEFAULT 0, last_error TEXT, result_json TEXT)""",
                """CREATE TABLE entities(
                    entity TEXT NOT NULL, entity_id TEXT NOT NULL, version INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
                    payload_json TEXT NOT NULL, official_number TEXT, PRIMARY KEY(entity, entity_id))""",
                """CREATE TABLE files(
                    file_id TEXT PRIMARY KEY, path TEXT NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL,
                    mime TEXT NOT NULL, upload_id TEXT, state TEXT NOT NULL)""",
            ),
            listOf("ALTER TABLE outbox ADD COLUMN resolved INTEGER NOT NULL DEFAULT 0"),
            // Reprise de transfert : taille de morceau figée par fichier (le serveur refuse un changement), octets confirmés.
            listOf(
                "ALTER TABLE files ADD COLUMN chunk_size INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE files ADD COLUMN sent_bytes INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE files ADD COLUMN last_error TEXT",
            ),
        )
    }
}
