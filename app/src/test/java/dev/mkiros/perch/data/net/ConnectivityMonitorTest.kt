package dev.mkiros.perch.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * D14/#47 — the real [ConnectivityMonitor], which every screen renders and no test drove.
 *
 * The two fakes are trivial and the callback is not: DESIGN.md §7's offline row has to be
 * right *at launch*, and a `NetworkCallback` only fires on changes. The bug this pins is
 * the one the production comment names — a device that is already offline when Perch opens
 * shows nothing until the network comes back and goes away again.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectivityMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    @Test
    fun `a device that is already offline says so before any change arrives`() = runTest {
        goOffline()

        val online = ConnectivityMonitor.system(context).observeOnline().first()

        assertThat(online).isFalse()
    }

    @Test
    fun `a device that is already online says so before any change arrives`() = runTest {
        goOnline()

        val online = ConnectivityMonitor.system(context).observeOnline().first()

        assertThat(online).isTrue()
    }

    @Test
    fun `losing the network is reported after the initial state`() = runTest {
        goOnline()
        val monitor = ConnectivityMonitor.system(context)

        val seen = mutableListOf<Boolean>()
        val collecting = launch { monitor.observeOnline().take(2).toList(seen) }
        // The callback is registered by the time the first value is out.
        while (seen.isEmpty()) kotlinx.coroutines.yield()
        shadowOf(manager).networkCallbacks.forEach { it.onLost(manager.activeNetwork!!) }
        collecting.join()

        assertThat(seen).containsExactly(true, false).inOrder()
    }

    /** The state is coarse on purpose: a network with no internet capability is offline. */
    @Test
    fun `a network without internet is not being online`() = runTest {
        goOnline()
        shadowOf(manager).setNetworkCapabilities(
            manager.activeNetwork,
            ShadowNetworkCapabilities.newInstance(),
        )

        assertThat(ConnectivityMonitor.system(context).observeOnline().first()).isFalse()
    }

    private fun goOnline() {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    private fun goOffline() {
        shadowOf(manager).setDefaultNetworkActive(false)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, null)
    }
}
