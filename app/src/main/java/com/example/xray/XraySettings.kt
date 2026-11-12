package com.example.xray

import android.content.Context

/** User switches for the Xray engine, persisted on the device. */
class XraySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** «فرگمنت (Fragment)»: split the TLS ClientHello of every proxy outbound. Default off. */
    var fragment: Boolean
        get() = prefs.getBoolean(KEY_FRAGMENT, false)
        set(value) { prefs.edit().putBoolean(KEY_FRAGMENT, value).apply() }

    companion object {
        const val PREFS = "xray_settings"
        const val KEY_FRAGMENT = "fragment"

        /** Ranking key: results with fragment on and off are kept apart for each network. */
        fun rankKey(networkKey: String, fragment: Boolean) = if (fragment) "$networkKey|frag" else networkKey
    }
}
