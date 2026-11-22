package ch.etasystems.pirol

import android.app.Application
import ch.etasystems.pirol.di.appModule
import ch.etasystems.pirol.ui.AppStrings
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.osmdroid.config.Configuration

class PirolApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppStrings.init(this)

        // osmdroid — zwingend vor erstem MapView-Konstruktor
        Configuration.getInstance().apply {
            load(this@PirolApp, getSharedPreferences("osmdroid", 0))
            userAgentValue = packageName
            // T108: persistenter Speicher (filesDir), damit heruntergeladene swisstopo-Kacheln bleiben.
            // Basispfad explizit setzen: alte Prefs zeigen teils auf die SD-Karte.
            val osmBase = java.io.File(filesDir, "osmdroid")
            osmdroidBasePath = osmBase
            osmdroidTileCache = java.io.File(osmBase, "tiles").also { it.mkdirs() }
            tileFileSystemCacheMaxBytes = 600L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 500L * 1024 * 1024
            // Kacheln gelten ein Jahr als frisch, damit sie offline nicht verfallen
            expirationOverrideDuration = 365L * 24 * 3600 * 1000
            tileDownloadThreads = 4.toShort()
            tileDownloadMaxQueueSize = 40.toShort()
        }

        startKoin {
            androidLogger()
            androidContext(this@PirolApp)
            modules(appModule)
        }

        // T133: gespeicherten Resampler-Modus aktivieren
        ch.etasystems.pirol.audio.dsp.AudioResampler.defaultMode =
            ch.etasystems.pirol.audio.dsp.AudioResampler.Mode.parse(
                ch.etasystems.pirol.data.AppPreferences(this).resamplerMode
            )

        // T187: Modell im Hintergrund freigeben (nur ohne Aufnahme)
        registerActivityLifecycleCallbacks(
            ch.etasystems.pirol.ml.ModelReleaseObserver(
                isRecording = { org.koin.core.context.GlobalContext.get().get<ch.etasystems.pirol.audio.RecordingPipeline>().isRunning },
                release = { org.koin.core.context.GlobalContext.get().get<ch.etasystems.pirol.ml.AudioClassifier>().releaseIfIdle() }
            )
        )
    }
}
