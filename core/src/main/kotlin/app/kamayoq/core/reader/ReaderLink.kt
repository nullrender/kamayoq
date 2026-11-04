package app.kamayoq.core.reader

/** A link inside a book. The fork would open it outside the sandbox, so the app always decides. */
sealed interface ReaderLink {
    /** Leaves the app only after the person sees this address. */
    data class AskFirst(val url: String) : ReaderLink
    data object Refused : ReaderLink

    companion object {
        // An allow list: browsers ignore tabs and newlines inside a scheme, so `java\tscript:` is
        // `javascript:`, and a list of bad schemes would never be complete. Printable ASCII only, and
        // no `user@` before the host: the confirmation shows this text as the real address, so nothing
        // in it may change how it reads.
        private val webAddress = Regex("""https?://[^/?#@\x00-\x20\x7F-￿]+([/?#][\x21-\x7E]*)?""", RegexOption.IGNORE_CASE)

        fun classify(href: String): ReaderLink {
            if (!webAddress.matches(href)) return Refused
            val scheme = href.substringBefore(':')
            return AskFirst(scheme.lowercase() + href.removePrefix(scheme))
        }
    }
}
