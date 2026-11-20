package app.kamayoq.android

/**
 * The four letters the text reader's panel offers (reader-design, DESIGN.md, "Tipografia"), inside
 * the APK so they do not depend on the phone. They sit beside the reader's page, under `reader/`,
 * where its WebView reads its files. Which one is the default is decided on the S24.
 */
enum class ReadingFont(val family: String, val files: List<String>, val license: String) {
    LITERATA(
        "Literata",
        listOf("reader/fonts/Literata.ttf", "reader/fonts/Literata-Italic.ttf"),
        "licenses/fonts/literata-OFL.txt",
    ),
    EB_GARAMOND(
        "EB Garamond",
        listOf("reader/fonts/EBGaramond.ttf", "reader/fonts/EBGaramond-Italic.ttf"),
        "licenses/fonts/eb-garamond-OFL.txt",
    ),
    IBM_PLEX_SERIF(
        "IBM Plex Serif",
        listOf(
            "reader/fonts/IBMPlexSerif-Regular.ttf",
            "reader/fonts/IBMPlexSerif-Italic.ttf",
            "reader/fonts/IBMPlexSerif-Bold.ttf",
            "reader/fonts/IBMPlexSerif-BoldItalic.ttf",
        ),
        "licenses/fonts/ibm-plex-serif-OFL.txt",
    ),
    ATKINSON_HYPERLEGIBLE(
        "Atkinson Hyperlegible",
        listOf(
            "reader/fonts/AtkinsonHyperlegible-Regular.ttf",
            "reader/fonts/AtkinsonHyperlegible-Italic.ttf",
            "reader/fonts/AtkinsonHyperlegible-Bold.ttf",
            "reader/fonts/AtkinsonHyperlegible-BoldItalic.ttf",
        ),
        "licenses/fonts/atkinson-hyperlegible-OFL.txt",
    ),
}
