package com.trevor.assistant

import android.content.Context

object TrevorVersion {
    fun name(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun label(context: Context): String = "v${name(context)}"
}
