package app.kamayoq.android

import app.kamayoq.core.progress.ChapterProgress
import app.kamayoq.core.reader.ComicInfo
import app.kamayoq.core.reader.ImageSignals
import app.kamayoq.core.reader.ImageSize
import app.kamayoq.core.reader.MangaField
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

// Slice 6 of the v1 interface: what the image reader's Automatic reads of the chapter it opens.
class ImageSignalsTest {

    private fun page(width: Int?, height: Int?) = PageImage(width, height) { ByteArrayInputStream(ByteArray(0)) }

    @Test
    fun `dado um capítulo aberto, então os sinais trazem os tamanhos, as palavras do Kavita e do ComicInfo e o Manga`() {
        val chapter = OpenedChapter(
            "chapter",
            listOf(page(690, 1300), page(null, null), page(690, 1290)),
            ChapterProgress.NEVER_OPENED,
            ComicInfo(listOf("Romance"), listOf("Long Strip"), MangaField.NO),
        ) {}

        assertEquals(
            ImageSignals(
                sizes = listOf(ImageSize(690, 1300), null, ImageSize(690, 1290)),
                words = listOf("Drama", "Romance", "Long Strip"),
                manga = MangaField.NO,
            ),
            imageSignals(chapter, workWords = listOf("Drama")),
        )
    }
}
