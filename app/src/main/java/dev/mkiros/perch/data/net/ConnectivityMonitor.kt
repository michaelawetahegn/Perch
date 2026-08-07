package dev.mkiros.perch.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Whether the device can reach the network at all (DESIGN.md §7's offline row).
 *
 * A `fun interface` rather than a concrete class because being offline is a state the UI
 * has to *render*, and rendering it in a test must not require a shadow network: a test
 * hands the view model [AlwaysOnline], [AlwaysOffline], or a flow it drives itself.
 *
 * This is deliberately coarse. It answers "is there a network", not "is this feed
 * reachable" — a source that is failing for its own reasons already has `lastError`, and
 * conflating the two would put an offline banner over a working list.
 */
fun interface ConnectivityMonitor {

    /** Emits the current state immediately, then again on every change. */
    fun observeOnline(): Flow<Boolean>

    companion object {

        /** The default everywhere a real network is not the thing under test. */
        val AlwaysOnline = ConnectivityMonitor { flowOf(true) }

        val AlwaysOffline = ConnectivityMonitor { flowOf(false) }

        /**
         * The real thing, over [ConnectivityManager]'s default-network callback.
         *
         * The callback only fires on *changes*, so the current state is sent before
         * registering — otherwise a device that is already offline at launch would show
         * no banner until the network came back and went away again.
         */
        fun system(context: Context): ConnectivityMonitor {
            val app = context.applicationContext
            return ConnectivityMonitor {
                callbackFlow {
                    val manager = app.getSystemService(ConnectivityManager::class.java)
                    if (manager == null) {
                        send(true)
                        awaitClose { }
                        return@callbackFlow
                    }
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            trySend(true)
                        }

                        override fun onLost(network: Network) {
                            trySend(false)
                        }
                    }
                    trySend(manager.hasInternet())
                    manager.registerDefaultNetworkCallback(callback)
                    awaitClose { manager.unregisterNetworkCallback(callback) }
                }.distinctUntilChanged()
            }
        }

        private fun ConnectivityManager.hasInternet(): Boolean {
            val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
