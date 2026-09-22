package com.lef.pmaaobd.stats

import android.app.Application
import android.content.Context
import timber.log.Timber

class App : Application() {

    val logTree = CacheLogTree()

    override fun onCreate() {
        super.onCreate()
        Timber.plant(logTree)
        fixAndroid14Perms()
    }

    /** Android 14 refuses to load writable dex files extracted by the Android Auto SDK. */
    private fun fixAndroid14Perms() {
        for (file in getDir("car_sdk_impl", Context.MODE_PRIVATE).listFiles() ?: emptyArray()) {
            if (file.isDirectory) {
                for (subfile in file.listFiles() ?: emptyArray()) {
                    subfile.setReadOnly()
                }
            }
            file.setReadOnly()
        }
    }
}
