package com.example.warp

import com.example.core.Json

/**
 * Psiphon tunnel-core configuration (pure, testable).
 *
 * The propagation/sponsor IDs and signature keys are Psiphon's public defaults for open-source
 * clients. Servers come from the embedded server-entry list shipped in the app. When
 * [upstreamSocksPort] is set, every Psiphon dial goes through that SOCKS proxy (the WARP layer).
 */
object PsiphonConfig {
    /** Protocols that work through a TCP-only SOCKS upstream. */
    val CHAINABLE = listOf(
        "FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH", "TLS-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH",
        "UNFRONTED-MEEK-OSSH", "SHADOWSOCKS-OSSH", "OSSH", "SSH",
    )

    /** Protocols for a direct dial on Iranian networks: fronted meek first, then TLS on 443. */
    val DIRECT = listOf("FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH", "TLS-OSSH", "SHADOWSOCKS-OSSH")

    fun build(dataDir: String, socksPort: Int, upstreamSocksPort: Int?, timeoutSeconds: Int): String {
        val c = linkedMapOf<String, Any?>(
            "PropagationChannelId" to "FFFFFFFFFFFFFFFF",
            "SponsorId" to "1111111111111111",
            "ClientVersion" to "1",
            "ClientPlatform" to "Android_MohrVpn",
            "DataRootDirectory" to dataDir,
            "LocalSocksProxyPort" to socksPort,
            "DisableLocalHTTPProxy" to true,
            "EgressRegion" to "",
            "DeviceRegion" to "IR",
            "EstablishTunnelTimeoutSeconds" to timeoutSeconds,
            "RemoteServerListSignaturePublicKey" to REMOTE_SERVER_LIST_KEY,
            "ServerEntrySignaturePublicKey" to SERVER_ENTRY_KEY,
            "ExchangeObfuscationKey" to EXCHANGE_KEY,
            "EmitDiagnosticNotices" to true,
            "EmitBytesTransferred" to false,
            "ConnectionWorkerPoolSize" to 10,
            "InproxyEnabled" to false,
            // Psiphon's public server-list locations (base64 URLs, as the config format expects).
            "RemoteServerListURLs" to listOf(mapOf("URL" to REMOTE_SERVER_LIST_URL, "OnlyAfterAttempts" to 0, "SkipVerify" to false)),
            "ObfuscatedServerListRootURLs" to listOf(mapOf("URL" to OSL_ROOT_URL, "OnlyAfterAttempts" to 0, "SkipVerify" to false)),
            "RemoteServerListDownloadFilename" to "remote_server_list",
        )
        if (upstreamSocksPort != null) {
            c["UpstreamProxyURL"] = "socks5://127.0.0.1:$upstreamSocksPort"
            c["LimitTunnelProtocols"] = CHAINABLE
        } else {
            c["LimitTunnelProtocols"] = DIRECT
            c["MeekTransformedHostName"] = "cdnjs.cloudflare.com"
            c["MeekSNIServerName"] = "cdnjs.cloudflare.com"
        }
        return Json.write(c)
    }

    const val REMOTE_SERVER_LIST_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
    /** https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed */
    const val REMOTE_SERVER_LIST_URL = "aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tLy9wc2lwaG9uL3dlYi9tanI0LXAyM3ItcHV3bC9zZXJ2ZXJfbGlzdF9jb21wcmVzc2Vk"
    /** https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/osl */
    const val OSL_ROOT_URL = "aHR0cHM6Ly9zMy5hbWF6b25hd3MuY29tL3BzaXBob24vd2ViL21qcjQtcDIzci1wdXdsL29zbA=="
    const val SERVER_ENTRY_KEY = "sHuUVTWaRyh5pZwy4UguSgkwmBe0EHtJJkoF5WrxmvA="
    const val EXCHANGE_KEY = "DpXzloJk1Hw6aSzmKKky0xcahsEHubch81Mi6K0XMlU="
}
