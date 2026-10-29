package eu.camemake.camevision

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

enum class AppRole { Unset, Usb, Bluetooth }

object AppPrefs {
    val role = MutableStateFlow(AppRole.Unset)
    val language = MutableStateFlow("")

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        language.value = prefs.getString(KEY_LANG, "") ?: ""
        role.value = when (prefs.getString(KEY_ROLE, "")) {
            "usb" -> AppRole.Usb
            "ble" -> AppRole.Bluetooth
            else -> AppRole.Unset
        }
    }

    fun setLanguage(context: Context, tag: String) {
        val value = if (tag == "zh") "zh" else "en"
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, value)
            .commit()
        language.value = value
    }

    fun setRole(context: Context, value: AppRole) {
        val wire = if (value == AppRole.Bluetooth) "ble" else "usb"
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROLE, wire)
            .commit()
        role.value = value
    }

    fun selectLanguage(context: Context, tag: String) {
        val next = if (tag == "zh") "zh" else "en"
        val current = language.value.ifBlank { "en" }
        setLanguage(context, next)
        if (current != next) (context as? Activity)?.recreate()
    }

    fun wrap(base: Context): Context {
        val locale = if (language.value == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    private const val NAME = "camevision_boot"
    private const val KEY_LANG = "lang"
    private const val KEY_ROLE = "role"
}
