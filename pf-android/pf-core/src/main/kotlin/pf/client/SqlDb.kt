package pf.client

/** Ligne de résultat : colonne -> Long, Double, String, ByteArray ou null. */
typealias Row = Map<String, Any?>

fun Row.str(name: String): String? = this[name]?.toString()
fun Row.long(name: String): Long = (this[name] as Number).toLong()
fun Row.longOrNull(name: String): Long? = (this[name] as Number?)?.toLong()
fun Row.double(name: String): Double = (this[name] as Number).toDouble()

/**
 * Accès SQL minimal que la bibliothèque exige. Implémentations : SQLiteDatabase (pf-android) et JDBC (tests JVM).
 *
 * Contrat : [transaction] ouvre une transaction écrivante (BEGIN IMMEDIATE), valide à la sortie normale et annule
 * sur exception ; les appels imbriqués rejoignent la transaction extérieure. Les instructions hors transaction
 * sont validées immédiatement. Toutes les méthodes sont sûres entre threads.
 */
interface SqlDb : AutoCloseable {
    fun execute(sql: String, vararg args: Any?)
    fun query(sql: String, vararg args: Any?): List<Row>
    fun <T> transaction(block: () -> T): T
    fun userVersion(): Int
    fun setUserVersion(version: Int)
}

fun SqlDb.queryOne(sql: String, vararg args: Any?): Row? = query(sql, *args).firstOrNull()
