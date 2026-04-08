package ch.etasystems.pirol

import android.app.Application
import ch.etasystems.pirol.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class PirolApp : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@PirolApp)
            modules(appModule)
        }
    }
}
