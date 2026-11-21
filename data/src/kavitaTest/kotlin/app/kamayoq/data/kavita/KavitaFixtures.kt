package app.kamayoq.data.kavita

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** Writes the comics the CI Kavita scans: only generated content, nothing from anyone else. */
fun main(args: Array<String>) {
    val root = File(args.single())
    val comics = File(root, "comics")
    writeChapter(comics, "First Work", number = 1, pages = 12)
    writeChapter(comics, "Second Work", number = 1, pages = 3)
    writeChapter(comics, "Second Work", number = 2, pages = 3)
    // Tests change these files, so they live in a library of their own.
    val mutable = File(root, "mutable")
    writeChapter(mutable, "Stable Work", number = 1, pages = 3)
    writeChapter(mutable, "Replaced Work", number = 1, pages = 3)
    writeChapter(mutable, "Growing Work", number = 1, pages = 3)
    writeChapter(mutable, "Vanishing Work", number = 1, pages = 3)
    writeChapter(mutable, "Vanishing Work", number = 2, pages = 3)
    writeChapter(mutable, "Swapped Work", number = 1, pages = 3)
    writeChapter(mutable, "Renamed Work", number = 1, pages = 3)
    writeChapter(mutable, "Returning Work", number = 1, pages = 3)
    writeChapter(mutable, "Returning Work", number = 2, pages = 3)
    writeComic(File(mutable, "Ranged Work/Ranged Work Ch. 001-003.cbz"), pages = 3)
    writeComic(File(mutable, "Two Files Work/Two Files Work Ch. 001.cbz"), pages = 3)
    writeComic(File(mutable, "Two Files Work/Two Files Work Ch. 001 extra.cbz"), pages = 4)
    writeComic(File(mutable, "Mixed Work/Mixed Work Vol. 1 Ch. 001.cbz"), pages = 3)
    writeComic(File(mutable, "Mixed Work/Mixed Work Vol. 2 Ch. 001.cbz"), pages = 3)
    writeComic(File(mutable, "Mixed Work/Mixed Work Ch. 005.cbz"), pages = 3)
    writeComic(File(mutable, "Mixed Work/Mixed Work SP01.cbz"), pages = 3)
    for (number in 1..14) writeChapter(mutable, "Synced Work", number, pages = 12)
    for (number in 1..3) writeChapter(mutable, "Crossed Work", number, pages = 12)
    for (number in 1..3) writeChapter(mutable, "Moved Work", number, pages = 12)
    for (number in 1..2) writeChapter(mutable, "Interrupted Work", number, pages = 12)
    for (number in 1..2) writeChapter(mutable, "Opened Work", number, pages = 12)
    for (number in 1..2) writeChapter(mutable, "Raced Work", number, pages = 12)
    for (number in 1..2) writeChapter(mutable, "Closed Work", number, pages = 12)
    writeChapter(mutable, "Unchecked Work", number = 1, pages = 12)
    writeChapter(mutable, "Reopened Work", number = 1, pages = 12)
    writeChapter(mutable, "Skipped Work", number = 1, pages = 12)
    // Read with the library's metadata on: what Kavita takes from ComicInfo.xml for the Work screen.
    val described = File(root, "described")
    writeChapter(described, "Info Work", number = 1, pages = 3, comicInfo = comicInfo("Info Work", 1))
    writeChapter(described, "Info Work", number = 2, pages = 3, comicInfo = comicInfo("Info Work", 2))
    writeChapter(described, "Ended Work", number = 1, pages = 3, comicInfo = comicInfo("Ended Work", 1, count = 2))
    writeChapter(described, "Ended Work", number = 2, pages = 3, comicInfo = comicInfo("Ended Work", 2, count = 2))
    writeChapter(described, "Short Work", number = 1, pages = 3, comicInfo = comicInfo("Short Work", 1, count = 5))
    writeChapter(described, "Plain Work", number = 1, pages = 3)
    writeChapter(described, "Edited Work", number = 1, pages = 3)
    // The same kind of file in a library whose metadata is off.
    writeChapter(mutable, "Unread Info Work", number = 1, pages = 3, comicInfo = comicInfo("Unread Info Work", 1, count = 1))
    // A Light Novel library: what Kavita says about a novel of one file, of volumes and of chapters.
    val novels = File(root, "novels")
    writeEpub(File(novels, "Single Novel/Single Novel.epub"), "Single Novel")
    for (volume in 1..2) writeEpub(File(novels, "Volume Novel/Volume Novel Vol. $volume.epub"), "Volume Novel")
    for (chapter in 1..2) writeEpub(File(novels, "Chapter Novel/Chapter Novel Ch. 00$chapter.epub"), "Chapter Novel")
    val books = File(root, "books")
    for (name in listOf("A file", "B file", "C file")) writeEpub(File(books, "Same Title/$name.epub"), "Same Title")
    writeEpub(File(books, "Lone Book/Lone Book.epub"), "Lone Book")
    // The middle section is out of the linear reading and the table of contents points into a
    // section: both decide whether Kavita's page is the spine index foliate-js counts.
    writeEpub(
        File(books, "Spine Book/Spine Book.epub"),
        "Spine Book",
        nonLinear = setOf(1),
        contents = listOf("s0.xhtml" to "One", "s1.xhtml" to "Two", "s2.xhtml" to "Three", "s2.xhtml#x" to "Three, part"),
    )
    // Named as numbered chapters of one work, with each file's own title.
    writeEpub(File(books, "Chapters Folder/Cap. 0928.epub"), "Alpha Story")
    writeEpub(File(books, "Chapters Folder/Cap. 0929.epub"), "Beta Story")
    // One book per progress test, so no test finds a record another one left.
    writeEpub(
        File(books, "Described Book/Described Book.epub"),
        "Described Book",
        metadata = "<dc:creator>Machado de Assis</dc:creator><dc:description>A book about Bento.</dc:description><dc:subject>Romance</dc:subject>",
    )
    for (name in listOf("Unmarked Book", "Marked Book", "Id Book", "Zero Book")) writeEpub(File(books, "$name/$name.epub"), name)
    // Read by the sync tests, one book each.
    val synced = listOf("Read Here Book", "First Section Book", "Ended Book", "Adopted Book", "Read There Book", "Marker Book", "Crossed Book", "Recounted Book", "Offline Book", "Reread Book", "Odd Marker Book", "Kept Here Book", "Batch Marker Book")
    for (name in synced) writeEpub(File(books, "$name/$name.epub"), name)
}

/** An EPUB of three sections, with invented text: the book is what the OPF title says. */
internal fun writeEpub(
    file: File,
    title: String,
    nonLinear: Set<Int> = emptySet(),
    contents: List<Pair<String, String>> = listOf("s0.xhtml" to "x"),
    metadata: String = "",
) {
    file.parentFile.mkdirs()
    val sections = 0..2
    val items = sections.joinToString("") { """<item id="s$it" href="s$it.xhtml" media-type="application/xhtml+xml"/>""" }
    val spine = sections.joinToString("") {
        if (it in nonLinear) """<itemref idref="s$it" linear="no"/>""" else """<itemref idref="s$it"/>"""
    }
    val links = contents.joinToString("") { (href, label) -> """<li><a href="$href">$label</a></li>""" }
    val draft = File(file.parentFile, "${file.name}.draft")
    ZipOutputStream(draft.outputStream()).use { zip ->
        fun entry(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
        entry("mimetype", "application/epub+zip")
        entry(
            "META-INF/container.xml",
            """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
                """<rootfiles><rootfile full-path="c.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
        )
        entry(
            "c.opf",
            """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">""" +
                """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:language>pt</dc:language>""" +
                """<dc:identifier id="id">${UUID.randomUUID()}</dc:identifier>$metadata</metadata>""" +
                """<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$items</manifest>""" +
                """<spine>$spine</spine></package>""",
        )
        entry(
            "nav.xhtml",
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>""" +
                """<nav epub:type="toc"><ol>$links</ol></nav></body></html>""",
        )
        for (section in sections) {
            entry(
                "s$section.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$section</title></head><body><p>$title $section</p><p id="x">$section</p></body></html>""",
            )
        }
    }
    check(draft.renameTo(file)) { "Could not rename $draft" }
}

internal fun writeChapter(root: File, work: String, number: Int, pages: Int, comicInfo: String? = null) {
    writeComic(File(root, "$work/$work Ch. %03d.cbz".format(number)), pages, comicInfo = comicInfo)
}

/** Invented summary, writers and genres; [count] is the number of chapters the series will have. */
internal fun comicInfo(series: String, number: Int, count: Int? = null): String =
    """<?xml version="1.0"?><ComicInfo><Series>$series</Series><Number>$number</Number>""" +
        (count?.let { "<Count>$it</Count>" } ?: "") +
        """<Summary>A summary of $series.</Summary><Writer>Ann Writer, Bo Second</Writer>""" +
        """<Genre>Fantasy, Action</Genre><Tags>Magic, School</Tags><LanguageISO>pt</LanguageISO></ComicInfo>"""

internal fun writeComic(file: File, pages: Int, extra: String? = null, comicInfo: String? = null) {
    file.parentFile.mkdirs()
    // Written aside and renamed, so the folder's date changes too, as the scanner compares it.
    val draft = File(file.parentFile, "${file.name}.draft")
    ZipOutputStream(draft.outputStream()).use { zip ->
        for (page in 1..pages) {
            zip.putNextEntry(ZipEntry("$page.png"))
            zip.write(png(color = page * 0x101010))
            zip.closeEntry()
        }
        if (comicInfo != null) {
            zip.putNextEntry(ZipEntry("ComicInfo.xml"))
            zip.write(comicInfo.toByteArray())
            zip.closeEntry()
        }
        if (extra != null) {
            zip.putNextEntry(ZipEntry("note.txt"))
            zip.write(extra.toByteArray())
            zip.closeEntry()
        }
    }
    check(draft.renameTo(file)) { "Could not rename $draft" }
}

// One known color per page, so a test can tell which page came back from its pixels.
private fun png(color: Int): ByteArray {
    val image = BufferedImage(60, 90, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until image.width) {
        for (y in 0 until image.height) image.setRGB(x, y, color)
    }
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
