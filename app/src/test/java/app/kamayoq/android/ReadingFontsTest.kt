package app.kamayoq.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingFontsTest {

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    private fun text(asset: String) = app.assets.open(asset).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `dado o APK, então as quatro letras de leitura do painel estão nele como fontes TrueType`() {
        assertEquals(
            listOf("Literata", "EB Garamond", "IBM Plex Serif", "Atkinson Hyperlegible"),
            ReadingFont.entries.map { it.family },
        )
        for (font in ReadingFont.entries) {
            for (file in font.files) {
                val start = app.assets.open(file).use { it.readNBytes(4) }
                assertArrayEquals("$file não é TrueType", byteArrayOf(0, 1, 0, 0), start)
            }
        }
    }

    // The OFL asks every copy of the font to carry its notice and the license (condition 2).
    @Test
    fun `dado cada família de letra no APK, então a licença OFL dela vai junto`() {
        val licenses = ReadingFont.entries.map { it.license } +
            listOf("licenses/fonts/bricolage-grotesque-OFL.txt", "licenses/fonts/figtree-OFL.txt")
        for (license in licenses) {
            val body = text(license)
            assertTrue("$license não é a OFL 1.1", body.contains("SIL Open Font License, Version 1.1"))
            assertTrue("$license não traz o aviso de copyright", body.startsWith("Copyright"))
        }
    }
}
