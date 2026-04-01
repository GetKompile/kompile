package {{packageName}}

import android.app.Application
import android.util.Log
import {{packageName}}.config.AppConfig

class KompileChatApplication : Application() {

    companion object {
        private const val TAG = "KompileChat"
        lateinit var instance: KompileChatApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Initializing {{projectName}} application")
        Log.i(TAG, "Inference mode: ${AppConfig.inferenceMode}")
        Log.i(TAG, "Model ID: ${AppConfig.modelId}")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.i(TAG, "{{projectName}} application terminated")
    }
}
