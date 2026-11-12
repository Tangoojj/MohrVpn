package com.example.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import java.net.Inet6Address

/** Iranian mobile operators, keyed by the SIM's MCC+MNC. */
enum class Operator(val label: String) {
    MCI("همراه اول"),
    IRANCELL("ایرانسل"),
    RIGHTEL("رایتل"),
    UNKNOWN("نامشخص");

    companion object {
        fun fromMccMnc(code: String): Operator = when (code.trim()) {
            "43211", "43219" -> MCI
            "43235" -> IRANCELL
            "43220" -> RIGHTEL
            else -> UNKNOWN
        }
    }
}

/** The physical network a connect runs on (never the VPN itself). */
data class NetworkSnapshot(
    val network: Network?,
    val isWifi: Boolean,
    val operator: Operator,
    val hasIpv6: Boolean,
    /** Stable key used to remember DPI type and config ranking per network. */
    val key: String,
) {
    val label: String get() = if (isWifi) "Wi‑Fi" else operator.label
}

object NetworkInfo {
    fun snapshot(context: Context): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val simCode = runCatching { tm?.simOperator.orEmpty() }.getOrDefault("")
        val operator = Operator.fromMccMnc(simCode)

        val network = underlying(cm)
        val caps = network?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val props = network?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
        val hasIpv6 = props?.linkAddresses?.any { la ->
            val a = la.address
            a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isLoopbackAddress &&
                (a.address[0].toInt() and 0xfe) != 0xfc
        } == true
        val key = if (wifi) {
            // Without location permission the SSID is hidden; the gateway/DNS set tells Wi-Fi networks apart.
            val gw = props?.routes?.mapNotNull { it.gateway?.hostAddress }?.filter { it != "0.0.0.0" && it != "::" }?.sorted().orEmpty()
            val dns = props?.dnsServers?.mapNotNull { it.hostAddress }?.sorted().orEmpty()
            "wifi:" + (gw + dns).joinToString(",").hashCode().toUInt().toString(16)
        } else {
            "cell:" + simCode.ifBlank { "none" }
        }
        return NetworkSnapshot(network, wifi, operator, hasIpv6, key)
    }

    /** Active non-VPN network: the default one, or any validated internet network. */
    @Suppress("DEPRECATION")
    fun underlying(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return active
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return underlying(cm) != null
    }
}
