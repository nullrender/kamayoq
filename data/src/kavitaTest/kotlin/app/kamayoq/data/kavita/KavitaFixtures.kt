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
    val books = File(root, "books")
    for (name in listOf("A file", "B file", "C file")) writeEpub(File(books, "Same Title/$name.epub"), "Same Title")
    writeEpub(File(books, "Lone Book/Lone Book.epub"), "Lone Book")
}

/** An EPUB of three sections, with invented text: the book is what the OPF title says. */
internal fun writeEpub(file: File, title: String) {
    file.parentFile.mkdirs()
    val sections = 0..2
    val items = sections.joinToString("") { """<item id="s$it" href="s$it.xhtml" media-type="application/xhtml+xml"/>""" }
    val spine = sections.joinToString("") { """<itemref idref="s$it"/>""" }
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
                """<dc:identifier id="id">${UUID.randomUUID()}</dc:identifier></metadata>""" +
                """<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$items</manifest>""" +
                """<spine>$spine</spine></package>""",
        )
        entry(
            "nav.xhtml",
            """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>""" +
                """<nav epub:type="toc"><ol><li><a href="s0.xhtml">x</a></li></ol></nav></body></html>""",
        )
        for (section in sections) {
            entry(
                "s$section.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$section</title></head><body><p>$title $section</p></body></html>""",
            )
        }
    }
    check(draft.renameTo(file)) { "Could not rename $draft" }
}

internal fun writeChapter(root: File, work: String, number: Int, pages: Int) {
    writeComic(File(root, "$work/$work Ch. %03d.cbz".format(number)), pages)
}

internal fun writeComic(file: File, pages: Int, extra: String? = null) {
    file.parentFile.mkdirs()
    // Written aside and renamed, so the folder's date changes too, as the scanner compares it.
    val draft = File(file.parentFile, "${file.name}.draft")
    ZipOutputStream(draft.outputStream()).use { zip ->
        for (page in 1..pages) {
            zip.putNextEntry(ZipEntry("$page.png"))
            zip.write(png(color = page * 0x101010))
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
