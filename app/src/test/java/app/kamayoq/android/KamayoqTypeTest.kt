package app.kamayoq.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.ResourceFont
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KamayoqTypeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    // Looked up by name, so a missing file fails the check instead of the build.
    private fun fontId(name: String) = app.resources.getIdentifier(name, "font", R::class.java.packageName)

    private val bricolage get() = fontId("bricolage_grotesque")
    private val figtree get() = fontId("figtree")

    private fun filesOf(family: FontFamily?): Set<Int> =
        (family as? FontListFontFamily)?.fonts.orEmpty().mapNotNull { (it as? ResourceFont)?.resId }.toSet()

    private fun typography(): Typography {
        lateinit var typography: Typography
        compose.setContent { KamayoqTheme(dark = true) { typography = MaterialTheme.typography } }
        compose.waitForIdle()
        return typography
    }

    @Test
    fun `dado o APK, então as letras Bricolage Grotesque e Figtree estão nele como fontes TrueType`() {
        for ((name, id) in listOf("bricolage_grotesque" to bricolage, "figtree" to figtree)) {
            assertNotEquals("$name não está no APK", 0, id)
            val start = app.resources.openRawResource(id).use { it.readNBytes(4) }
            assertArrayEquals("$name não é TrueType", byteArrayOf(0, 1, 0, 0), start)
        }
    }

    @Test
    fun `dado o tema, então os títulos usam Bricolage Grotesque e o resto da interface usa Figtree`() {
        val type = typography()
        val titles = mapOf(
            "displayLarge" to type.displayLarge, "displayMedium" to type.displayMedium, "displaySmall" to type.displaySmall,
            "headlineLarge" to type.headlineLarge, "headlineMedium" to type.headlineMedium, "headlineSmall" to type.headlineSmall,
            "titleLarge" to type.titleLarge,
        )
        val rest = mapOf(
            "titleMedium" to type.titleMedium, "titleSmall" to type.titleSmall,
            "bodyLarge" to type.bodyLarge, "bodyMedium" to type.bodyMedium, "bodySmall" to type.bodySmall,
            "labelLarge" to type.labelLarge, "labelMedium" to type.labelMedium, "labelSmall" to type.labelSmall,
        )
        titles.forEach { (role, style) -> assertEquals(role, setOf(bricolage), filesOf(style.fontFamily)) }
        rest.forEach { (role, style) -> assertEquals(role, setOf(figtree), filesOf(style.fontFamily)) }
    }

    // DESIGN.md, principle 6: no text below 12 px.
    @Test
    fun `dado o tema, então nenhum estilo de texto fica abaixo de 12`() {
        val type = typography()
        val styles: List<Pair<String, TextStyle>> = listOf(
            "bodySmall" to type.bodySmall, "labelMedium" to type.labelMedium, "labelSmall" to type.labelSmall,
            "titleSmall" to type.titleSmall, "bodyMedium" to type.bodyMedium,
        )
        styles.forEach { (role, style) -> assertTrue("$role tem ${style.fontSize}", style.fontSize.value >= 12f) }
    }
}
