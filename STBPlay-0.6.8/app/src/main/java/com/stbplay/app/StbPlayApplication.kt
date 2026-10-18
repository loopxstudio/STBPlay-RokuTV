package com.stbplay.app

class StbPlayApplication : android.app.Application() {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Media-library exception logs can include credential-bearing provider URLs.
        // Keep local safe-tag diagnostics in our app, but silence library logs in release.
        if (!BuildConfig.DEBUG) androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        com.stbplay.app.data.SecureValues.initialize(this)
    }
}
