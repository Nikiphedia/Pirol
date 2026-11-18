package ch.etasystems.pirol.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

/**
 * T120a: Zugriff auf lokalisierte Strings ausserhalb von Composables
 * (ViewModels, Services, Toasts). Beruecksichtigt die Pro-App-Sprache aus
 * AppCompatDelegate, auch wenn der Application-Context noch die Systemsprache hat.
 */
object AppStrings {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Context mit der aktuell gewaehlten App-Sprache (oder Systemsprache, falls keine gewaehlt) */
    fun localizedContext(base: Context? = appContext): Context {
        val ctx = base ?: error("AppStrings.init() wurde nicht aufgerufen")
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return ctx
        val platform = locales.unwrap() as? android.os.LocaleList ?: return ctx
        val cfg = Configuration(ctx.resources.configuration)
        cfg.setLocales(platform)
        return ctx.createConfigurationContext(cfg)
    }

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val ctx = localizedContext()
        return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    }
}
