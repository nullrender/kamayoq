package app.kamayoq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FolderConventionTest {

    private fun read(vararg paths: String) = FolderConvention.read(paths.toList())

    private fun LocalLibrary.work(title: String) = works.single { it.title == title }

    private fun Work.numbers() = units.map { it.number }

    private fun Work.names() = units.map { it.name }

    @Test
    fun `dado a série Sakamoto Days do exemplo, quando a pasta é lida, então há uma obra de Quadrinhos com dois capítulos na ordem`() {
        val library = read(
            "Comics/Sakamoto Days/cover.jpg",
            "Comics/Sakamoto Days/ComicInfo.xml",
            "Comics/Sakamoto Days/Cap. 002/002.jpg",
            "Comics/Sakamoto Days/Cap. 002/001.jpg",
            "Comics/Sakamoto Days/Cap. 001.cbz",
        )

        val work = library.works.single()
        assertEquals("Comics/Sakamoto Days", work.path)
        assertEquals("Sakamoto Days", work.title)
        assertNull(work.alternativeTitle)
        assertEquals(WorkType.COMICS, work.type)
        assertNull(work.subtype)
        assertEquals(
            listOf(
                ReadingUnit(
                    path = "Comics/Sakamoto Days/Cap. 001.cbz",
                    name = "Cap. 001",
                    kind = UnitKind.CHAPTER,
                    number = 1.0,
                    content = UnitContent.Archive,
                ),
                ReadingUnit(
                    path = "Comics/Sakamoto Days/Cap. 002",
                    name = "Cap. 002",
                    kind = UnitKind.CHAPTER,
                    number = 2.0,
                    content = UnitContent.ImageFolder(
                        listOf(
                            "Comics/Sakamoto Days/Cap. 002/001.jpg",
                            "Comics/Sakamoto Days/Cap. 002/002.jpg",
                        ),
                    ),
                ),
            ),
            work.units,
        )
        // The cover and ComicInfo.xml are known files, not chapters and not warnings.
        assertEquals(emptyList(), library.ignored)
    }

    @Test
    fun `dado as pastas de tipo Comics, Novels e Books com outra caixa, então o tipo é reconhecido`() {
        val library = read(
            "COMICS/A/Cap. 1.cbz",
            "novels/B/Cap. 1.cbz",
            "Books/C/Vol. 1.cbz",
        )

        val types = library.works.associate { it.title to (it.type to it.subtype) }
        assertEquals(WorkType.COMICS to null, types["A"])
        assertEquals(WorkType.NOVELS to null, types["B"])
        assertEquals(WorkType.BOOKS to null, types["C"])
    }

    @Test
    fun `dado pastas de tipo em português ou de subtipo, então não são pasta de tipo e o conteúdo fica de fora à vista`() {
        // Decision of 27/10/2026: one English name per type, and no subtype folders.
        val library = read(
            "Quadrinhos/A/Cap. 1.cbz",
            "Livros/B/Vol. 1.cbz",
            "Manhwa/C/Cap. 1.cbz",
            "Comics/Manga/D/Cap. 1.cbz",
        )

        assertEquals(emptyList(), library.works)
        assertEquals(
            listOf(
                "Comics/Manga/D/Cap. 1.cbz",
                "Livros/B/Vol. 1.cbz",
                "Manhwa/C/Cap. 1.cbz",
                "Quadrinhos/A/Cap. 1.cbz",
            ),
            library.ignored.map { it.path }.sorted(),
        )
        assertEquals(setOf(IgnoreReason.UNEXPECTED_PLACE), library.ignored.map { it.reason }.toSet())
    }

    @Test
    fun `dado um CBZ dentro de Novels, então vale a pasta de tipo e não o formato`() {
        val work = read("Novels/Omniscient Reader's Viewpoint/Cap. 0928.cbz").works.single()

        assertEquals(WorkType.NOVELS, work.type)
    }

    @Test
    fun `dado uma obra fora de pasta de tipo, então o tipo vem do formato`() {
        val library = read(
            "Sakamoto Days/Cap. 001.cbz",
            "Avulso.cbz",
        )

        assertEquals(WorkType.COMICS, library.work("Sakamoto Days").type)
        assertEquals(WorkType.COMICS, library.work("Avulso").type)
        assertNull(library.work("Sakamoto Days").subtype)
    }

    @Test
    fun `dado o título entre parênteses no fim do nome da pasta, então ele vira o título alternativo`() {
        val work = read("Comics/A Torre de Deus (Tower of God)/Cap. 0928.cbz").works.single()

        assertEquals("A Torre de Deus", work.title)
        assertEquals("Tower of God", work.alternativeTitle)
        assertEquals(listOf(928.0), work.numbers())
    }

    @Test
    fun `dado um CBZ solto na pasta de tipo, então é uma obra de um volume só`() {
        val work = read("Comics/One Shot.cbz").works.single()

        assertEquals("Comics/One Shot.cbz", work.path)
        assertEquals("One Shot", work.title)
        assertEquals(
            listOf(
                ReadingUnit(
                    path = "Comics/One Shot.cbz",
                    name = "One Shot",
                    kind = UnitKind.VOLUME,
                    number = null,
                    content = UnitContent.Archive,
                ),
            ),
            work.units,
        )
    }

    @Test
    fun `dado Cap 2 e Cap 10, então 2 vem antes de 10`() {
        val work = read(
            "Comics/X/Cap. 10.cbz",
            "Comics/X/Cap. 2.cbz",
        ).works.single()

        assertEquals(listOf(2.0, 10.0), work.numbers())
    }

    @Test
    fun `dado Vol 01 e Cap 099 na mesma pasta, então um volume e um capítulo, o volume primeiro`() {
        val work = read(
            "Comics/Gantz/Cap. 099.cbz",
            "Comics/Gantz/Vol. 01.cbz",
        ).works.single()

        assertEquals(listOf(UnitKind.VOLUME, UnitKind.CHAPTER), work.units.map { it.kind })
        assertEquals(listOf(1.0, 99.0), work.numbers())
    }

    @Test
    fun `dado volume e capítulo no mesmo nome, então é capítulo com o número do capítulo`() {
        val unit = read("Comics/X/Vol. 2 Ch. 15.cbz").works.single().units.single()

        assertEquals(UnitKind.CHAPTER, unit.kind)
        assertEquals(15.0, unit.number)
    }

    @Test
    fun `dado capítulos que recomeçam a cada volume, então a ordem é pelo volume, depois pelo capítulo, e os avulsos vêm depois`() {
        // Rule E3, as in Kavita: volumes by number, then loose chapters.
        val work = read(
            "Comics/X/Cap. 9.cbz",
            "Comics/X/Vol. 2 Cap. 1.cbz",
            "Comics/X/Vol. 1 Cap. 2.cbz",
            "Comics/X/Vol. 2 Cap. 2.cbz",
            "Comics/X/Vol. 1 Cap. 1.cbz",
        ).works.single()

        assertEquals(
            listOf("Vol. 1 Cap. 1", "Vol. 1 Cap. 2", "Vol. 2 Cap. 1", "Vol. 2 Cap. 2", "Cap. 9"),
            work.units.map { it.name },
        )
    }

    @Test
    fun `dado dois arquivos deixados de fora com o mesmo caminho, então a lista traz um só`() {
        // A provider can list a name twice; the list on screen needs each path once.
        val ignored = read("Comics/notas.txt", "Comics/notas.txt").ignored

        assertEquals(listOf("Comics/notas.txt"), ignored.map { it.path })
    }

    @Test
    fun `dado um grupo com volume no nome, então ele não dá volume ao capítulo nem muda a ordem`() {
        // A volume in the group name is not the volume of the chapter.
        val work = read("Comics/X/Vol. 9 Scans_Chapter 2.cbz", "Comics/X/Cap. 1.cbz").works.single()

        assertEquals(listOf("Cap. 1", "Vol. 9 Scans_Chapter 2"), work.units.map { it.name })
    }

    @Test
    fun `dado nomes de capítulo baixados pelo Mihon, então o número sai do nome`() {
        // Mihon names a download "<scanlator>_<chapter name>", with "_<6 hex>" at the end when the
        // chapter name hash option is on (DownloadProvider.kt, commit d001e6f).
        val work = read(
            "Comics/X/Asura Scans_Chapter 12.cbz",
            "Comics/X/Chapter 12.5_a1b2c3.cbz",
            "Comics/X/Group_Ch. 3 - The Gate.cbz",
            "Comics/X/Episode 7.cbz",
        ).works.single()

        assertEquals(listOf(3.0, 7.0, 12.0, 12.5), work.numbers())
    }

    @Test
    fun `dado um grupo com número no nome e um capítulo sem número, então ele fica sem número, e só o número sozinho antes do traço baixo vale`() {
        // A number in the group name is not the number of the chapter.
        val work = read(
            "Comics/X/Chapter 5 Scans_Extra.cbz",
            "Comics/X/Cap. 6.cbz",
            "Comics/X/Cap. 5_final.cbz",
        ).works.single()

        assertEquals(listOf("Cap. 5_final", "Cap. 6", "Chapter 5 Scans_Extra"), work.units.map { it.name })
        assertEquals(listOf(5.0, 6.0, null), work.numbers())
    }

    @Test
    fun `dado nomes do Mihon com o capítulo só em número, então o número sai do trecho depois do grupo`() {
        // The group name used to decide the order.
        val work = read(
            "Comics/X/ZScan_2_abcdef.cbz",
            "Comics/X/AScan_10_a1b2c3.cbz",
            "Comics/X/Grupo_003.cbz",
            "Comics/X/004_a1b2c3.cbz",
        ).works.single()

        assertEquals(listOf(2.0, 3.0, 4.0, 10.0), work.numbers())
    }

    @Test
    fun `dado capítulos do Mihon com o sufixo de 6 letras e números, então o nome mostrado fica sem ele`() {
        // Seen on a test phone (25/10/2026): "Capítulo 2_0a052f" in the work's list.
        val work = read(
            "Comics/X/Capítulo 2_0a052f.cbz",
            "Comics/X/Capítulo 10_b071b1/001.jpg",
            "Comics/X/Capítulo 1_GrupoX_Mihon.cbz",
        ).works.single()

        assertEquals(listOf("Capítulo 1_GrupoX_Mihon", "Capítulo 2", "Capítulo 10"), work.names())
        // The file is still found by its own name.
        assertEquals("Comics/X/Capítulo 2_0a052f.cbz", work.units[1].path)
    }

    @Test
    fun `dado um grupo cujo nome tem palavra de capítulo, então vale o número do nome do capítulo`() {
        val unit = read("Comics/X/Chapter 5 Scans_Chapter 12.cbz").works.single().units.single()

        assertEquals(12.0, unit.number)
    }

    @Test
    fun `dado nomes com sublinhado e sem número de capítulo, então continuam sem número`() {
        val work = read(
            "Comics/X/Re_Zero.cbz",
            "Comics/X/Grupo_Especial.cbz",
            "Comics/X/Season_2_Recap.cbz",
        ).works.single()

        assertEquals(listOf(null, null, null), work.numbers())
    }

    @Test
    fun `dado nomes em português, então o número sai do nome, também com vírgula decimal`() {
        val work = read(
            "Comics/X/Capítulo 5.cbz",
            "Comics/X/Cap 6.zip",
            "Comics/X/Cap. 7,5.cbz",
            "Comics/X/Volume 1.cbz",
        ).works.single()

        assertEquals(listOf(1.0, 5.0, 6.0, 7.5), work.numbers())
        assertEquals(UnitKind.VOLUME, work.units.first().kind)
    }

    @Test
    fun `dado um nome que é só um número, então ele é o número do capítulo`() {
        val work = read(
            "Comics/X/002.cbz",
            "Comics/X/001.cbz",
        ).works.single()

        assertEquals(listOf(1.0, 2.0), work.numbers())
    }

    @Test
    fun `dado capítulos sem número, então vêm depois dos numerados, em ordem natural`() {
        val work = read(
            "Comics/X/Posfácio.cbz",
            "Comics/X/Extra 10.cbz",
            "Comics/X/Cap. 2.cbz",
            "Comics/X/Especial.cbz",
            "Comics/X/Extra 2.cbz",
            "Comics/X/Cap. 1.cbz",
        ).works.single()

        assertEquals(listOf("Cap. 1", "Cap. 2", "Especial", "Extra 2", "Extra 10", "Posfácio"), work.names())
        assertEquals(listOf(1.0, 2.0, null, null, null, null), work.numbers())
    }

    @Test
    fun `dado dois capítulos com o mesmo número, então os dois ficam, em ordem natural do nome`() {
        val work = read(
            "Comics/X/Group B_Chapter 5.cbz",
            "Comics/X/Group A_Chapter 5.cbz",
        ).works.single()

        assertEquals(listOf("Group A_Chapter 5", "Group B_Chapter 5"), work.names())
    }

    @Test
    fun `dado uma pasta de imagens, então as páginas ficam em ordem natural`() {
        val unit = read(
            "Comics/X/Cap. 1/10.jpg",
            "Comics/X/Cap. 1/2.webp",
            "Comics/X/Cap. 1/1.png",
        ).works.single().units.single()

        assertEquals(
            UnitContent.ImageFolder(
                listOf("Comics/X/Cap. 1/1.png", "Comics/X/Cap. 1/2.webp", "Comics/X/Cap. 1/10.jpg"),
            ),
            unit.content,
        )
    }

    @Test
    fun `dado extensões em maiúsculas, então os arquivos são reconhecidos`() {
        val work = read(
            "Comics/X/Cap. 3.CBZ",
            "Comics/X/Cap. 4.Zip",
            "Comics/X/Cap. 5/01.JPG",
        ).works.single()

        assertEquals(listOf(3.0, 4.0, 5.0), work.numbers())
    }

    @Test
    fun `dado a capa com outra caixa ou formato, então continua sendo capa e não capítulo`() {
        val library = read(
            "Comics/X/Cover.PNG",
            "Comics/X/Cap. 1.cbz",
        )

        assertEquals(listOf(1.0), library.works.single().numbers())
        assertEquals(emptyList(), library.ignored)
    }

    @Test
    fun `dado um EPUB em Novels, por ora, então ele aparece como ignorado e não some calado`() {
        val library = read(
            "Novels/Release That Witch/Release That Witch.epub",
            "Novels/Cap. 0928.epub",
        )

        assertEquals(emptyList(), library.works)
        assertEquals(
            listOf(
                IgnoredFile("Novels/Cap. 0928.epub", IgnoreReason.UNSUPPORTED_FORMAT),
                IgnoredFile("Novels/Release That Witch/Release That Witch.epub", IgnoreReason.UNSUPPORTED_FORMAT),
            ),
            library.ignored,
        )
    }

    @Test
    fun `dado Books-Dom Casmurro epub, quando a pasta é lida, então há uma obra de Livros com um volume EPUB`() {
        val library = read("Books/Dom Casmurro.epub")

        val work = library.works.single()
        assertEquals("Books/Dom Casmurro.epub", work.path)
        assertEquals("Dom Casmurro", work.title)
        assertEquals(WorkType.BOOKS, work.type)
        assertEquals(
            listOf(ReadingUnit("Books/Dom Casmurro.epub", "Dom Casmurro", UnitKind.VOLUME, null, UnitContent.Epub)),
            work.units,
        )
        assertEquals(emptyList(), library.ignored)
    }

    @Test
    fun `dado Books-Os Miseráveis-Vol 1 epub, quando a pasta é lida, então o volume está na obra`() {
        val library = read(
            "Books/Os Miseráveis/Vol. 2.epub",
            "Books/Os Miseráveis/Vol. 1.epub",
        )

        val work = library.work("Os Miseráveis")
        assertEquals(WorkType.BOOKS, work.type)
        assertEquals(
            listOf(
                ReadingUnit("Books/Os Miseráveis/Vol. 1.epub", "Vol. 1", UnitKind.VOLUME, 1.0, UnitContent.Epub),
                ReadingUnit("Books/Os Miseráveis/Vol. 2.epub", "Vol. 2", UnitKind.VOLUME, 2.0, UnitContent.Epub),
            ),
            work.units,
        )
        assertEquals(emptyList(), library.ignored)
    }

    @Test
    fun `dado um EPUB fora de Books, então ele continua ignorado até os romances chegarem`() {
        val library = read(
            "Solto.epub",
            "Comics/X/Extra.epub",
            "Comics/X/Cap. 1.cbz",
        )

        assertEquals(listOf("X"), library.works.map { it.title })
        assertEquals(
            listOf(
                IgnoredFile("Solto.epub", IgnoreReason.UNSUPPORTED_FORMAT),
                IgnoredFile("Comics/X/Extra.epub", IgnoreReason.UNSUPPORTED_FORMAT),
            ),
            library.ignored,
        )
    }

    @Test
    fun `dado um arquivo desconhecido dentro de uma pasta de imagens, então ele aparece como ignorado`() {
        val library = read(
            "Comics/X/Cap. 1/01.jpg",
            "Comics/X/Cap. 1/ComicInfo.xml",
            "Comics/X/Cap. 1/notes.txt",
        )

        assertEquals(
            UnitContent.ImageFolder(listOf("Comics/X/Cap. 1/01.jpg")),
            library.works.single().units.single().content,
        )
        assertEquals(listOf(IgnoredFile("Comics/X/Cap. 1/notes.txt", IgnoreReason.UNSUPPORTED_FORMAT)), library.ignored)
    }

    @Test
    fun `dado arquivos fora do lugar previsto, então aparecem como ignorados`() {
        val library = read(
            "solta.jpg",
            "Comics/capa-solta.png",
            "Comics/X/pagina.jpg",
            "Comics/X/Vol. 1/Cap. 1.cbz",
            "Comics/X/pt-BR/Cap. 1.cbz",
            "Comics/X/Cap. 2/extra/1.jpg",
            "Comics/X/Cap. 3.cbz",
        )

        assertEquals(listOf(3.0), library.work("X").numbers())
        assertEquals(
            setOf(
                IgnoredFile("solta.jpg", IgnoreReason.UNEXPECTED_PLACE),
                IgnoredFile("Comics/capa-solta.png", IgnoreReason.UNEXPECTED_PLACE),
                IgnoredFile("Comics/X/pagina.jpg", IgnoreReason.UNEXPECTED_PLACE),
                IgnoredFile("Comics/X/Vol. 1/Cap. 1.cbz", IgnoreReason.UNEXPECTED_PLACE),
                IgnoredFile("Comics/X/pt-BR/Cap. 1.cbz", IgnoreReason.UNEXPECTED_PLACE),
                IgnoredFile("Comics/X/Cap. 2/extra/1.jpg", IgnoreReason.UNEXPECTED_PLACE),
            ),
            library.ignored.toSet(),
        )
        assertEquals(6, library.ignored.size)
    }

    @Test
    fun `dado arquivos ocultos e o nomedia, então não viram capítulo nem aviso`() {
        val library = read(
            ".thumbnails/a.jpg",
            "Comics/X/.nomedia",
            "Comics/X/.trashed-1730000000-Cap. 9.cbz",
            "Comics/X/Cap. 1/.nomedia",
            "Comics/X/Cap. 1/01.jpg",
        )

        assertEquals(listOf("Cap. 1"), library.works.single().names())
        assertEquals(emptyList(), library.ignored)
    }
}
