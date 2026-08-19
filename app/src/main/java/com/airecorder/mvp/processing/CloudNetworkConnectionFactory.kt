package com.airecorder.mvp.processing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/**
 * Board sync may leave a manually selected recorder hotspot as the default route. Cloud work
 * must use a separately validated Internet network instead of that local-only WiFi network.
 */
class CloudNetworkUnavailableException : IllegalStateException(
    "Waiting for an internet connection after recorder sync"
)

fun interface HttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

class AndroidCloudConnectionFactory(context: Context) : HttpConnectionFactory {
    private val connectivityManager = requireNotNull(
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    )

    override fun open(url: URL): HttpURLConnection {
        val internetNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        } ?: throw CloudNetworkUnavailableException()

        return internetNetwork.openConnection(url) as HttpURLConnection
    }
}
