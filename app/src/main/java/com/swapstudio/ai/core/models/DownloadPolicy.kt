package com.swapstudio.ai.core.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Decides whether a model download may start right now.
 *
 * The "download on unmetered networks only" switch in Settings used to be stored and never read, so
 * a 1 GB model pack could happily eat a mobile data plan. The policy is a plain class (no
 * Context kept) so [DownloadPolicy.evaluate] can be unit tested with a fake capability reporter.
 */
object DownloadPolicy {

    /** Why a download was refused, or [Allowed]. */
    sealed class Verdict {
        /** Go ahead. */
        data object Allowed : Verdict()

        /** The user is on a metered connection and asked us not to use it. */
        data object Metered : Verdict()

        /** No network at all. */
        data object Offline : Verdict()

        /** Human readable reason, shown on the model card. */
        val message: String?
            get() = when (this) {
                Allowed -> null
                Metered -> "Waiting for Wi-Fi: downloads are limited to unmetered networks"
                Offline -> "No network connection"
            }
    }

    /**
     * Pure decision function.
     *
     * @param wifiOnly the user setting (Settings → "Downloads on Wi-Fi only")
     * @param connected true when any network is available
     * @param unmetered true when the active network is not metered (Wi-Fi, Ethernet, ...)
     */
    fun evaluate(wifiOnly: Boolean, connected: Boolean, unmetered: Boolean): Verdict = when {
        !connected -> Verdict.Offline
        wifiOnly && !unmetered -> Verdict.Metered
        else -> Verdict.Allowed
    }

    /** Reads the current network state from the platform and applies [wifiOnly]. */
    fun current(context: Context, wifiOnly: Boolean): Verdict {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return Verdict.Allowed // no permission/manager: do not block the user
        val network = manager.activeNetwork ?: return Verdict.Offline
        val capabilities = manager.getNetworkCapabilities(network) ?: return Verdict.Offline
        val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        // NET_CAPABILITY_NOT_METERED is exactly "the user is not paying per byte": Wi-Fi, Ethernet and
        // tethering connections say yes, mobile data says no.
        val unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return evaluate(wifiOnly, connected, unmetered)
    }
}
