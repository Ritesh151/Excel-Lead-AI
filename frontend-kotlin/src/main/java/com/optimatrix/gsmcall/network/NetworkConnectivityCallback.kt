package com.optimatrix.gsmcall.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import com.optimatrix.gsmcall.utils.LogStore

class NetworkConnectivityCallback(private val context: Context) {

    companion object {
        private const val TAG = "NetworkCallback"
    }

    interface OnNetworkChangeListener {
        fun onWifiConnected()
        fun onWifiDisconnected()
        fun onWifiChanged(newNetwork: String)
        fun onCellularConnected()
        fun onNetworkLost()
        fun onNetworkAvailable()
        fun onNetworkUnavailable()
        fun onCaptivePortal()
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<OnNetworkChangeListener>()
    private var lastNetworkType: String? = null
    private var isRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            LogStore.log(TAG, "Network available: $network")
            val caps = connectivityManager.getNetworkCapabilities(network)
            val type = getNetworkType(caps)
            lastNetworkType = type
            mainHandler.post {
                listeners.forEach { it.onNetworkAvailable() }
                when {
                    type == "WIFI" -> listeners.forEach { it.onWifiConnected() }
                    type == "CELLULAR" -> listeners.forEach { it.onCellularConnected() }
                }
            }
        }

        override fun onLost(network: Network) {
            LogStore.log(TAG, "Network lost: $network")
            val wasWifi = lastNetworkType == "WIFI"
            lastNetworkType = null
            mainHandler.post {
                listeners.forEach { it.onNetworkLost() }
                if (wasWifi) {
                    listeners.forEach { it.onWifiDisconnected() }
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val newType = getNetworkType(networkCapabilities)
            if (newType != lastNetworkType) {
                LogStore.log(TAG, "Network changed: $lastNetworkType -> $newType")
                val oldType = lastNetworkType
                lastNetworkType = newType
                mainHandler.post {
                    if (oldType == "WIFI" && newType != "WIFI") {
                        listeners.forEach { it.onWifiDisconnected() }
                    } else if (newType == "WIFI") {
                        listeners.forEach { it.onWifiConnected() }
                        listeners.forEach { it.onWifiChanged(newType) }
                    }
                }
            }
        }

        override fun onUnavailable() {
            LogStore.log(TAG, "Network unavailable")
            lastNetworkType = null
            mainHandler.post {
                listeners.forEach { it.onNetworkUnavailable() }
            }
        }
    }

    fun register() {
        if (isRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true
            LogStore.log(TAG, "Network callback registered")
        } catch (e: Exception) {
            LogStore.log(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun unregister() {
        if (!isRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            LogStore.log(TAG, "Network callback unregistered")
        } catch (e: Exception) {
            LogStore.log(TAG, "Failed to unregister network callback: ${e.message}")
        }
    }

    fun addListener(listener: OnNetworkChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: OnNetworkChangeListener) {
        listeners.remove(listener)
    }

    fun isWifiConnected(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    fun getActiveNetworkType(): String {
        return try {
            val network = connectivityManager.activeNetwork ?: return "NONE"
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return "UNKNOWN"
            getNetworkType(caps)
        } catch (e: Exception) {
            "ERROR"
        }
    }

    private fun getNetworkType(caps: NetworkCapabilities?): String {
        if (caps == null) return "UNKNOWN"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
    }
}
