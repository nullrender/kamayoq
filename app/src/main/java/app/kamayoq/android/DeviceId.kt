package app.kamayoq.android

import android.content.Context
import java.util.UUID

/**
 * Which device a reading position came from (progress contract, section 2): made once, kept in
 * the app's own storage, never tied to the person or the hardware.
 */
object DeviceId {
    fun of(context: Context): String {
        val preferences = context.getSharedPreferences("device", Context.MODE_PRIVATE)
        preferences.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        preferences.edit().putString(KEY, id).apply()
        return id
    }

    private const val KEY = "device_id"
}
