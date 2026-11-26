package com.github.aura.asbox.util

import android.content.Context

object AppContextHolder {
    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun context(): Context? {
        return appContext
    }
}
