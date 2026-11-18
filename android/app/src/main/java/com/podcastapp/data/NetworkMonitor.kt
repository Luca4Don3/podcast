package com.podcastapp.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** Follow the app's default route (API 24+), not every attached network. */
class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun defaultRoute(): Network? = cm.activeNetwork

    // A LAN or VPN can be useful even when Android has not validated public Internet access.
    fun isOnline(): Boolean = defaultRoute() != null

    fun isUnmetered(): Boolean {
        val network = defaultRoute() ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Preserve route identity so that online-to-online handoffs can retry pending work. */
    fun observeRoutes(): Flow<Network?> = callbackFlow {
        val state = DefaultRouteState<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = synchronized(state) {
                state.available(network)
                trySend(state.current)
                Unit
            }

            override fun onLost(network: Network) = synchronized(state) {
                state.lost(network)
                trySend(state.current)
                Unit
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        // Read the initial snapshot outside callbacks; do not overwrite any callback already received.
        val initial = defaultRoute()
        synchronized(state) {
            state.seed(initial)
            trySend(state.current)
        }
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()
}

/** Mutated under the monitor's lock. Stale losses must not reset the replacement route. */
internal class DefaultRouteState<T : Any> {
    private var callbackReceived = false
    var current: T? = null
        private set

    fun seed(route: T?) {
        if (!callbackReceived) current = route
    }

    fun available(route: T) {
        callbackReceived = true
        current = route
    }

    fun lost(route: T) {
        // Before seeding, a lost callback is also enough to invalidate the initial snapshot.
        callbackReceived = true
        if (current == route) current = null
    }
}
