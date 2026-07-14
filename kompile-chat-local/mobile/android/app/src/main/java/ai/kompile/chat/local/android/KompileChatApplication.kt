package ai.kompile.chat.local.android

import android.app.Application
import android.util.Log

/**
 * Application class. Currently thin — wires up the global [AppPreferences]
 * instance so every component can access settings without a Context parameter.
 */
class KompileChatApplication : Application() {

    companion object {
        private const val TAG = "KompileChat"
        lateinit var instance: KompileChatApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "KompileChatApplication started")
    }
}
