package ch.etasystems.pirol

import android.app.Application
import ch.etasystems.pirol.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.osmdroid.config.Configuration

class PirolApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // osmdroid — zwingend vor erstem MapView-Konstruktor
        Configuration.getInstance().apply {
            load(this@PirolApp, getSharedPreferences("osmdroid", 0))
            userAgentValue = packageName
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid_tiles")
            tileDownloadThreads = 4.toShort()
            tileDownloadMaxQueueSize = 40.toShort()
        }

        startKoin {
            androidLogger()
            androidContext(this@PirolApp)
            modules(appModule)
        }
    }
}
