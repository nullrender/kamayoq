package app.kamayoq.android

import android.content.res.Resources

/**
 * What Home says after Download on one chapter of Arrived: [count] is how many went in line, and
 * [here] says the chapter is on the device already, done between Home's last reading and the tap.
 */
internal fun homeDownloadNotice(resources: Resources, count: Int, here: Boolean): String =
    if (count > 0) resources.getQuantityString(R.plurals.download_started, count, count)
    else resources.getString(R.string.download_already_coming)
