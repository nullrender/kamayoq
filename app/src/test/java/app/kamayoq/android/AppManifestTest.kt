package app.kamayoq.android

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppManifestTest {
    private val app = ApplicationProvider.getApplicationContext<KamayoqApp>()

    @Test
    fun `dado o app instalado, então ele pode usar a rede`() {
        val permissions = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()

        assertTrue(Manifest.permission.INTERNET in permissions)
    }

    @Test
    fun `dado um Kavita em casa sem HTTPS, então o app aceita um endereço http`() {
        assertNotEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
    }
}
