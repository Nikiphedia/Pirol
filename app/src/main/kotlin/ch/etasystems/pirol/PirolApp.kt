package ch.etasystems.pirol

import android.app.Application
import ch.etasystems.pirol.di.appModule
import ch.etasystems.pirol.ml.SpeciesNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get

class PirolApp : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@PirolApp)
            modules(appModule)
        }

        // SpeciesNameResolver vorab laden (T53) — async, kein ANR-Risiko
        val resolver: SpeciesNameResolver = get(SpeciesNameResolver::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            resolver.load()
        }
    }
}
