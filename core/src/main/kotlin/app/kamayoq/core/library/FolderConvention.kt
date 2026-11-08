package app.kamayoq.core.library

import java.text.Normalizer

/**
 * Reads the folder convention (`docs/decisions/folder-convention-2026-10-24.md`) from the paths of
 * the files under the folder the person chose, relative to it and separated by `/`. Works only on
 * names: nothing here opens a file, so it runs the same on the JVM and on the phone.
 */
object FolderConvention {
    private val archiveExtensions = setOf("cbz", "zip")
    private const val EPUB = "epub"
    internal val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "avif")

    // One English name per type, matched after removing accents and case, so "COMICS" and "comics"
    // are the same folder. No subtype folders: the subtype comes from metadata (decision of 27/10/2026).
    private val typeFolders: Map<String, WorkType> = mapOf(
        "comics" to WorkType.COMICS,
        "novels" to WorkType.NOVELS,
        "books" to WorkType.BOOKS,
    )

    // Every format read today is made of images, so a work outside a type folder is comics (rule 1).
    private val typeByFormat: WorkType = WorkType.COMICS

    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""

    // (?iu): case-insensitive also for accented letters. The look-behind keeps "ch" inside a word
    // from counting; "_" is not a letter, so Mihon's "<scanlator>_Chapter 12" still matches.
    private val chapterNumber =
        Regex("""(?iu)(?<!\p{L})(?:cap[ií]tulo|cap|chapter|ch|epis[oó]dio|episode|ep)\.?\s*$NUMBER""")
    private val volumeNumber = Regex("""(?iu)(?<!\p{L})(?:volume|vol)\.?\s*$NUMBER""")
    private val bareNumber = Regex(NUMBER)

    // Mihon names a download "<scanlator>_<chapter name>", with "_<6 hex>" at the end when its
    // chapter name hash option is on (DownloadProvider.kt, commit d001e6f).
    private val mihonHash = Regex("""_[0-9a-f]{6}$""")

    // The greedy title leaves only the last parenthesized group as the second title.
    private val titleWithAlternative = Regex("""^(.+?)\s*\(([^()]+)\)\s*$""")

    // Rule E3, as in Kavita: volumes by number, each with its chapters, then loose chapters; the
    // unnumbered go last.
    private val unitOrder: Comparator<ReadingUnit> =
        compareBy<ReadingUnit> {
            when {
                it.number == null -> 2
                volumeOf(it) != null -> 0
                else -> 1
            }
        }
            .thenBy { volumeOf(it) }
            .thenBy { if (it.kind == UnitKind.VOLUME) 0 else 1 }
            .thenBy { it.number }
            .then(compareBy<ReadingUnit, String>(NaturalOrder) { it.name })
            .thenBy { it.path }

    fun read(paths: List<String>): LocalLibrary {
        val root = Folder("")
        for (path in paths) root.add(path.split('/').filter { it.isNotEmpty() })

        val scan = Scan()
        scan.readContainer(root, typeFolderAllowed = true, type = typeByFormat)
        return LocalLibrary(
            works = scan.works.sortedWith(compareBy<Work, String>(NaturalOrder) { it.path }),
            ignored = scan.ignored.distinct(),
        )
    }

    private class Folder(val path: String) {
        val folders = LinkedHashMap<String, Folder>()
        val files = mutableListOf<String>()

        fun add(segments: List<String>) {
            if (segments.isEmpty()) return
            if (segments.size == 1) {
                files += segments[0]
            } else {
                folders.getOrPut(segments[0]) { Folder(childPath(path, segments[0])) }.add(segments.drop(1))
            }
        }
    }

    private class Scan {
        val works = mutableListOf<Work>()
        val ignored = mutableListOf<IgnoredFile>()

        /** The chosen folder (where type folders may be) or a type folder: holds works. */
        fun readContainer(folder: Folder, typeFolderAllowed: Boolean, type: WorkType) {
            for (name in folder.files) {
                if (isSkipped(name)) continue
                val path = childPath(folder.path, name)
                when (extension(name)) {
                    in archiveExtensions -> works += looseWork(path, name, type, UnitContent.Archive)
                    EPUB -> if (readsEpub(type)) {
                        works += looseWork(path, name, type, UnitContent.Epub)
                    } else {
                        ignore(path, IgnoreReason.UNSUPPORTED_FORMAT)
                    }
                    in imageExtensions -> ignore(path, IgnoreReason.UNEXPECTED_PLACE)
                    else -> ignore(path, IgnoreReason.UNSUPPORTED_FORMAT)
                }
            }
            for ((name, sub) in folder.folders) {
                if (isHidden(name)) continue
                val typeFolder = if (typeFolderAllowed) typeFolders[fold(name)] else null
                if (typeFolder != null) {
                    readContainer(sub, typeFolderAllowed = false, type = typeFolder)
                } else {
                    readWork(sub, name, type)
                }
            }
        }

        private fun looseWork(path: String, name: String, type: WorkType, content: UnitContent): Work {
            val stem = stem(name)
            val (title, alternative) = titles(stem)
            val unit = ReadingUnit(
                path = path,
                name = stem,
                kind = UnitKind.VOLUME,
                number = numbering(stem).second,
                content = content,
            )
            return Work(path, title, alternative, type, subtype = null, units = listOf(unit))
        }

        private fun readWork(folder: Folder, name: String, type: WorkType) {
            val units = mutableListOf<ReadingUnit>()
            for (fileName in folder.files) {
                if (isSkipped(fileName) || isCover(fileName)) continue
                val path = childPath(folder.path, fileName)
                when (extension(fileName)) {
                    in archiveExtensions -> units += unit(path, stem(fileName), UnitContent.Archive)
                    EPUB -> if (readsEpub(type)) {
                        units += unit(path, stem(fileName), UnitContent.Epub)
                    } else {
                        ignore(path, IgnoreReason.UNSUPPORTED_FORMAT)
                    }
                    in imageExtensions -> ignore(path, IgnoreReason.UNEXPECTED_PLACE)
                    else -> ignore(path, IgnoreReason.UNSUPPORTED_FORMAT)
                }
            }
            for ((subName, sub) in folder.folders) {
                if (isHidden(subName)) continue
                imageFolder(sub)?.let { units += unit(sub.path, subName, it) }
            }
            if (units.isEmpty()) return
            val (title, alternative) = titles(name)
            works += Work(folder.path, title, alternative, type, subtype = null, units = units.sortedWith(unitOrder))
        }

        /**
         * A chapter folder holds its images directly. A folder without them (a volume or language
         * subfolder, not read yet) is not a chapter, and everything in it is out of place.
         */
        private fun imageFolder(folder: Folder): UnitContent.ImageFolder? {
            val pages = folder.files.filter { !isSkipped(it) && extension(it) in imageExtensions }
            if (pages.isEmpty()) {
                ignoreAll(folder)
                return null
            }
            for (name in folder.files) {
                if (isSkipped(name) || extension(name) in imageExtensions) continue
                val reason = if (extension(name) in archiveExtensions) {
                    IgnoreReason.UNEXPECTED_PLACE
                } else {
                    IgnoreReason.UNSUPPORTED_FORMAT
                }
                ignore(childPath(folder.path, name), reason)
            }
            for ((name, sub) in folder.folders) {
                if (!isHidden(name)) ignoreAll(sub)
            }
            return UnitContent.ImageFolder(pages.sortedWith(NaturalOrder).map { childPath(folder.path, it) })
        }

        private fun ignoreAll(folder: Folder) {
            for (name in folder.files) {
                if (!isSkipped(name)) ignore(childPath(folder.path, name), IgnoreReason.UNEXPECTED_PLACE)
            }
            for ((name, sub) in folder.folders) {
                if (!isHidden(name)) ignoreAll(sub)
            }
        }

        private fun ignore(path: String, reason: IgnoreReason) {
            ignored += IgnoredFile(path, reason)
        }

        /** The name shown leaves out Mihon's hash; the path keeps it, so the file is still found. */
        private fun unit(path: String, name: String, content: UnitContent): ReadingUnit {
            val (kind, number) = numbering(name)
            return ReadingUnit(path, name.replace(mihonHash, ""), kind, number, content)
        }
    }

    /**
     * Only Books reads an EPUB for now: in Novels a file may be one chapter or a whole compiled
     * novel, and telling them apart waits for milestone B of step 4 (`docs/research/epub-step-4-design.md`).
     */
    private fun readsEpub(type: WorkType) = type == WorkType.BOOKS

    /** Rule 4 and E3: a chapter number wins over a volume number; "Vol." alone makes a volume. */
    private fun numbering(name: String): Pair<UnitKind, Double?> {
        val unwrapped = name.replace(mihonHash, "")
        val places = numberPlaces(unwrapped)
        for (place in places) {
            chapterNumber.find(place)?.let { return UnitKind.CHAPTER to toNumber(it.groupValues[1]) }
        }
        for (place in places) {
            volumeNumber.find(place)?.let { return UnitKind.VOLUME to toNumber(it.groupValues[1]) }
        }
        // A bare number counts only as the whole name or as the part after the last "_".
        for (place in listOf(unwrapped, unwrapped.substringAfterLast('_'))) {
            bareNumber.matchEntire(place.trim())?.let { return UnitKind.CHAPTER to toNumber(it.groupValues[1]) }
        }
        return UnitKind.CHAPTER to null
    }

    /**
     * Where a name's numbers may be. The chapter name comes after the scanlator's "_", so a group
     * called "Chapter 5 Scans" does not give its number. What comes before only counts when it is
     * nothing but a number ("Cap. 5_final"), never a group's name.
     */
    private fun numberPlaces(name: String): List<String> {
        val afterGroup = name.substringAfter('_', "").ifEmpty { null }
        val beforeGroup = name.substringBefore('_').takeIf { afterGroup == null || isOnlyNumbering(it) }
        return listOfNotNull(afterGroup, beforeGroup)
    }

    private fun isOnlyNumbering(text: String) =
        chapterNumber.replace(volumeNumber.replace(text, ""), "").isBlank()

    private fun toNumber(text: String): Double = text.replace(',', '.').toDouble()

    /** The volume a unit is in (rule 3): its own number when it is a volume, or the "Vol. N" in a chapter's name. */
    private fun volumeOf(unit: ReadingUnit): Double? =
        if (unit.kind == UnitKind.VOLUME) {
            unit.number
        } else {
            numberPlaces(unit.name).firstNotNullOfOrNull { volumeNumber.find(it) }?.let { toNumber(it.groupValues[1]) }
        }

    private fun titles(name: String): Pair<String, String?> {
        val match = titleWithAlternative.matchEntire(name) ?: return name.trim() to null
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    private fun childPath(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun stem(name: String) = if ('.' in name) name.substringBeforeLast('.') else name

    /** Hidden files (`.nomedia`, Android's `.trashed-…`) and ComicInfo.xml are not content. */
    private fun isSkipped(name: String) = isHidden(name) || name.equals("ComicInfo.xml", ignoreCase = true)

    private fun isHidden(name: String) = name.startsWith('.')

    private fun isCover(name: String) =
        stem(name).equals("cover", ignoreCase = true) && extension(name) in imageExtensions

    private fun fold(name: String): String =
        Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "").lowercase()
}
