package eu.camemake.camevision

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import eu.camemake.camevision.ui.CameVisionApp
import eu.camemake.camevision.ui.CameVisionTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        AppPrefs.load(newBase)
        super.attachBaseContext(AppPrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CameVisionTheme {
                CameVisionApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (AppPrefs.role.value == AppRole.Usb) UsbHub.refresh(this)
    }
}
