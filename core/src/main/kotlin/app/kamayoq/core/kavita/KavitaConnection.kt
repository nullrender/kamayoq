package app.kamayoq.core.kavita

/** The answer to connecting; the screen says something different for each one. */
sealed interface KavitaConnection {
    /** [keySaved] is false when the device could not keep the key: the connection worked, this once. */
    data class Connected(val version: String, val keySaved: Boolean = true, val accountId: Int? = null) : KavitaConnection

    data object Refused : KavitaConnection

    data object Unreachable : KavitaConnection

    /** Something answered, and it did not answer as Kavita does. */
    data object NotKavita : KavitaConnection

    /** The key belongs to another account than the one this app has been reading with. */
    data object OtherAccount : KavitaConnection
}

/**
 * What Kavita shows for OPDS ends in /api/opds and may carry the key; any other path is a base path.
 * Found on a test phone, 31/10/2026.
 */
fun normalizeKavitaAddress(address: String): String = address.trim().substringBefore("/api/opds").trimEnd('/')
