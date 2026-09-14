package com.rohit.sosafe.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkStatusInfo(
    val isConnected: Boolean,
    val connectionType: String, // "WIFI", "CELLULAR (5G/4G)", "ETHERNET", "OFFLINE"
    val voiceQualityStatus: String, // "EXCELLENT (DIRECT VOICE OK)", "FAIR (LOW BANDWIDTH)", "NO INTERNET"
    val isVoiceCapable: Boolean
)

class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val networkStatusFlow: Flow<NetworkStatusInfo> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getNetworkStatusInfo())
            }

            override fun onLost(network: Network) {
                trySend(getNetworkStatusInfo())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(getNetworkStatusInfo())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        // Send initial network state immediately
        trySend(getNetworkStatusInfo())

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    fun getNetworkStatusInfo(): NetworkStatusInfo {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkStatusInfo(
            isConnected = false,
            connectionType = "OFFLINE",
            voiceQualityStatus = "NO INTERNET",
            isVoiceCapable = false
        )

        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkStatusInfo(
            isConnected = false,
            connectionType = "OFFLINE",
            voiceQualityStatus = "NO INTERNET",
            isVoiceCapable = false
        )

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (!hasInternet) {
            return NetworkStatusInfo(
                isConnected = false,
                connectionType = "LIMITED",
                voiceQualityStatus = "NO INTERNET",
                isVoiceCapable = false
            )
        }

        val connectionType = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE DATA"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "CONNECTED"
        }

        // Estimate bandwidth for WebRTC / PCM raw audio transmission (~64kbps required)
        val downstreamKbps = caps.linkDownstreamBandwidthKbps
        val upstreamKbps = caps.linkUpstreamBandwidthKbps

        // Voice transmission needs at least ~64kbps upstream and downstream
        val isVoiceCapable = downstreamKbps == 0 || (downstreamKbps >= 100 && upstreamKbps >= 100)

        val voiceQualityStatus = if (isVoiceCapable) {
            "EXCELLENT (VOICE OK)"
        } else {
            "POOR (AUDIO DELAY)"
        }

        return NetworkStatusInfo(
            isConnected = true,
            connectionType = connectionType,
            voiceQualityStatus = voiceQualityStatus,
            isVoiceCapable = isVoiceCapable
        )
    }
}
