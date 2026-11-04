package app.kamayoq.core.library

/** Numbers inside names compare by value, so "Cap. 2" comes before "Cap. 10"; case is ignored. */
internal object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""[0-9]+|[^0-9]+""")

    override fun compare(a: String, b: String): Int {
        val left = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val right = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val result = if (x[0] in '0'..'9' && y[0] in '0'..'9') compareDigits(x, y) else x.compareTo(y)
            if (result != 0) return result
        }
        return left.size - right.size
    }

    // Compared as text after dropping leading zeros, so any length of number fits.
    private fun compareDigits(x: String, y: String): Int {
        val a = x.trimStart('0')
        val b = y.trimStart('0')
        return if (a.length != b.length) a.length - b.length else a.compareTo(b)
    }
}
