package eu.camemake.camevision

import android.app.Application
import android.content.Context

class CameApp : Application() {
    override fun attachBaseContext(base: Context) {
        AppPrefs.load(base)
        super.attachBaseContext(AppPrefs.wrap(base))
    }
}
