package com.airecorder.mvp.core.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.location.LocationManager
import android.util.Log
import com.airecorder.mvp.core.ble.WifiCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class RecorderWifiJoinPhase {
    STARTING,
    CHECKING_SAVED_WIFI,
    WAITING_FOR_HOTSPOT,
    REQUESTING_SYSTEM_APPROVAL,
    WAITING_FOR_MANUAL_SELECTION,
    CONNECTED
}

class RecorderWifiConnector(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    suspend fun join(
        credentials: WifiCredentials,
        onProgress: (RecorderWifiJoinPhase) -> Unit = {}
    ): RecorderWifiLease {
        // A user may have saved recoder in Android's Wi-Fi settings. Let that
        // system-managed connection settle before asking for an app-specific
        // WifiNetworkSpecifier connection to the same SSID.
        onProgress(RecorderWifiJoinPhase.CHECKING_SAVED_WIFI)
        awaitSavedRecorderWifi(credentials.ssid)?.let { network ->
            Log.i(TAG, "SYNC-WIFI-SAVED-REUSE-004: using Android's existing recorder WiFi: ${credentials.ssid}")
            onProgress(RecorderWifiJoinPhase.CONNECTED)
            return lease(network) {}
        }

        // Huawei/Honor Android 10/11 can leave WifiNetworkSpecifier's system
        // approval sheet visible but non-interactive until the app is reinstalled.
        // Use the system Wi-Fi panel instead; the existing SSID watcher resumes
        // sync as soon as the user selects the recorder hotspot.
        if (requiresManualWifiSelection()) {
            Log.w(TAG, "SYNC-WIFI-EMUI-MANUAL-008: bypassing unstable Android approval sheet")
            requireWifiDiscoveryAvailable()
            onProgress(RecorderWifiJoinPhase.WAITING_FOR_HOTSPOT)
            awaitRecorderHotspot(credentials.ssid)
            onProgress(RecorderWifiJoinPhase.WAITING_FOR_MANUAL_SELECTION)
            return awaitManualRecorderWifi(credentials.ssid, onProgress)
        }

        Log.i(TAG, "Requesting a fresh recorder WiFi handoff: ${credentials.ssid}")
        onProgress(RecorderWifiJoinPhase.REQUESTING_SYSTEM_APPROVAL)
        try {
            // Android's system approval dialog owns this request. Do not start
            // the manual fallback concurrently: on EMUI that can cancel the
            // requested network while the user is still confirming it.
            return withTimeout(AUTO_WIFI_CALLBACK_WATCHDOG_MS) {
                requestNetwork(credentials, onProgress)
            }
        } catch (_: TimeoutCancellationException) {
            // A few EMUI builds leave the approval sheet visible without ever
            // delivering onAvailable or onUnavailable. The coroutine timeout
            // cancels the request and unregisters its callback via
            // invokeOnCancellation below, so the next Sync does not require an
            // app reinstall to create a new system request.
            Log.w(TAG, "SYNC-WIFI-AUTO-WATCHDOG-006: Android did not finish the recorder WiFi approval request")
            onProgress(RecorderWifiJoinPhase.WAITING_FOR_MANUAL_SELECTION)
            return awaitManualRecorderWifi(credentials.ssid, onProgress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Automatic recorder WiFi request ended; enabling manual fallback", error)
            onProgress(RecorderWifiJoinPhase.WAITING_FOR_MANUAL_SELECTION)
            return awaitManualRecorderWifi(credentials.ssid, onProgress)
        }
    }

    private suspend fun awaitSavedRecorderWifi(ssid: String): Network? {
        return withTimeoutOrNull(SAVED_WIFI_SETTLE_WAIT_MS) {
            while (currentCoroutineContext().isActive) {
                findConnectedRecorderNetwork(ssid)?.let { return@withTimeoutOrNull it }
                delay(MANUAL_WIFI_POLL_MS)
            }
            null
        }
    }

    private fun requireWifiDiscoveryAvailable() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !locationManager.isLocationEnabled) {
            throw RecorderWifiFailure(
                code = "SYNC-WIFI-LOCATION-SERVICES-016",
                message = "Android location services must be on to discover recorder WiFi on this phone"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitRecorderHotspot(ssid: String) {
        var observedScanResults = false
        val found = withTimeoutOrNull(HOTSPOT_VISIBILITY_WAIT_MS) {
            while (currentCoroutineContext().isActive) {
                runCatching { wifiManager.startScan() }
                val scanResults = try {
                    wifiManager.scanResults
                } catch (error: SecurityException) {
                    throw RecorderWifiFailure(
                        code = "SYNC-WIFI-SCAN-PERMISSION-018",
                        message = "Android did not allow the app to scan for recorder WiFi"
                    )
                }
                observedScanResults = observedScanResults || scanResults.isNotEmpty()
                if (scanResults.any { it.SSID == ssid }) return@withTimeoutOrNull true
                delay(HOTSPOT_SCAN_POLL_MS)
            }
            false
        } == true
        if (found) return

        // Some Android builds suppress third-party scan results even with permission.
        // In that case the system Wi-Fi panel remains the authoritative discovery UI.
        if (!observedScanResults) {
            Log.w(TAG, "SYNC-WIFI-SCAN-UNAVAILABLE-019: no scan results were exposed; continuing to system panel")
            return
        }
        throw RecorderWifiFailure(
            code = "SYNC-WIFI-HOTSPOT-NOT-VISIBLE-017",
            message = "Recorder confirmed WiFi mode, but hotspot $ssid was not visible after ${HOTSPOT_VISIBILITY_WAIT_MS / 1_000} seconds"
        )
    }

    private suspend fun awaitManualRecorderWifi(
        ssid: String,
        onProgress: (RecorderWifiJoinPhase) -> Unit
    ): RecorderWifiLease {
        return try {
            withTimeout<RecorderWifiLease>(MANUAL_WIFI_WAIT_MS) {
                while (currentCoroutineContext().isActive) {
                    findConnectedRecorderNetwork(ssid)?.let { network ->
                        Log.i(TAG, "Detected manually connected recorder WiFi; continuing directly to FTP: $ssid")
                        onProgress(RecorderWifiJoinPhase.CONNECTED)
                        return@withTimeout lease(network) {}
                    }
                    delay(MANUAL_WIFI_POLL_MS)
                }
                error("Recorder WiFi wait was cancelled")
            }
        } catch (_: TimeoutCancellationException) {
            throw RecorderWifiFailure(
                code = "SYNC-WIFI-MANUAL-WAIT-003",
                message = "Manual recorder WiFi was not detected after automatic connection failed"
            )
        }
    }

    private fun findConnectedRecorderNetwork(ssid: String): Network? {
        val wifiNetworks = connectivity.allNetworks.filter { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        wifiNetworks.firstOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            val wifiInfo = capabilities.transportInfo as? WifiInfo ?: return@firstOrNull false
            wifiInfo.ssid.normalizedSsid() == ssid
        }?.let { return it }

        // EMUI 11 / Android 10 often omits WifiInfo from NetworkCapabilities.
        // The legacy connectionInfo API still reports the selected SSID there.
        val connectedSsid = runCatching { wifiManager.connectionInfo?.ssid.normalizedSsid() }.getOrNull()
        if (connectedSsid != ssid) return null
        return wifiNetworks.firstOrNull { it == connectivity.activeNetwork } ?: wifiNetworks.firstOrNull()
    }

    private fun requiresManualWifiSelection(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return false
        val vendor = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        return vendor.contains("huawei") || vendor.contains("honor")
    }

    private suspend fun requestNetwork(
        credentials: WifiCredentials,
        onProgress: (RecorderWifiJoinPhase) -> Unit
    ): RecorderWifiLease {
        return suspendCancellableCoroutine { continuation ->
            val callbackHandler = Handler(Looper.getMainLooper())
            var callback: ConnectivityManager.NetworkCallback? = null
            var connectionPoll: Runnable? = null
            val requestFinished = AtomicBoolean(false)
            val callbackReleased = AtomicBoolean(false)

            fun stopPolling() {
                connectionPoll?.let(callbackHandler::removeCallbacks)
                connectionPoll = null
            }

            fun cleanup() {
                stopPolling()
                if (callbackReleased.compareAndSet(false, true)) {
                    callback?.let { registeredCallback ->
                        runCatching { connectivity.unregisterNetworkCallback(registeredCallback) }
                    }
                }
            }

            fun completeWithNetwork(network: Network, source: String) {
                if (requestFinished.compareAndSet(false, true)) {
                    stopPolling()
                    Log.i(TAG, "Recorder WiFi detected through $source; continuing to FTP: ${credentials.ssid}")
                    onProgress(RecorderWifiJoinPhase.CONNECTED)
                    if (continuation.isActive) {
                        continuation.resume(lease(network, ::cleanup))
                    } else {
                        cleanup()
                    }
                }
            }

            fun failBeforeNetworkIsAvailable(failure: RecorderWifiFailure) {
                if (requestFinished.compareAndSet(false, true) && continuation.isActive) {
                    cleanup()
                    continuation.resumeWithException(failure)
                }
            }
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(credentials.ssid)
                .setWpa2Passphrase(credentials.wifiPassword)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // EMUI can deliver onLost/onUnavailable after onAvailable. Once the
                    // approved network has been handed to FTP, those late callbacks must
                    // not cancel the still-active system Wi-Fi selection request.
                    if (!requestFinished.get()) {
                        completeWithNetwork(network, "Android network callback")
                    } else {
                        Log.d(TAG, "Ignoring late recorder WiFi onAvailable callback: ${credentials.ssid}")
                    }
                }

                override fun onUnavailable() {
                    Log.w(TAG, "Android reported recorder WiFi unavailable: ${credentials.ssid}")
                    failBeforeNetworkIsAvailable(
                        RecorderWifiFailure(
                            code = "SYNC-WIFI-AUTO-UNAVAILABLE-001",
                            message = "Android did not make recorder WiFi available"
                        )
                    )
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "Recorder WiFi network was lost: ${credentials.ssid}")
                    failBeforeNetworkIsAvailable(
                        RecorderWifiFailure(
                            code = "SYNC-WIFI-AUTO-LOST-002",
                            message = "Recorder WiFi was lost before the transfer could start"
                        )
                    )
                }
            }
            connectionPoll = object : Runnable {
                override fun run() {
                    if (requestFinished.get() || !continuation.isActive) return
                    findConnectedRecorderNetwork(credentials.ssid)?.let { network ->
                        Log.i(TAG, "SYNC-WIFI-SSID-POLL-007: connected SSID observed while Android approval callback was pending")
                        completeWithNetwork(network, "SSID polling")
                        return
                    }
                    callbackHandler.postDelayed(this, APPROVAL_WIFI_POLL_MS)
                }
            }
            try {
                connectivity.requestNetwork(request, requireNotNull(callback), AUTO_WIFI_PLATFORM_TIMEOUT_MS)
                connectionPoll?.let(callbackHandler::post)
            } catch (error: Exception) {
                failBeforeNetworkIsAvailable(
                    RecorderWifiFailure(
                        code = "SYNC-WIFI-AUTO-REQUEST-005",
                        message = "Android rejected the automatic WiFi request: ${error.message ?: error.javaClass.simpleName}"
                    )
                )
            }
            continuation.invokeOnCancellation {
                // Always release the Android request on coroutine cancellation. The
                // request may already have delivered a lease that has not yet entered
                // the caller's use block.
                cleanup()
            }
        }
    }

    private companion object {
        const val TAG = "RecorderWifiConnector"
        // The platform timeout releases normal rejected/ignored requests. A separate
        // coroutine watchdog above covers EMUI cases where the platform never calls
        // either onAvailable or onUnavailable.
        const val AUTO_WIFI_PLATFORM_TIMEOUT_MS = 25_000
        const val AUTO_WIFI_CALLBACK_WATCHDOG_MS = 30_000L
        const val APPROVAL_WIFI_POLL_MS = 250L
        const val HOTSPOT_VISIBILITY_WAIT_MS = 20_000L
        const val HOTSPOT_SCAN_POLL_MS = 1_000L
        const val MANUAL_WIFI_WAIT_MS = 120_000L
        const val MANUAL_WIFI_POLL_MS = 250L
        const val SAVED_WIFI_SETTLE_WAIT_MS = 5_000L
    }

    private fun lease(network: Network, releaseAction: () -> Unit): RecorderWifiLease = RecorderWifiLease(
        network = network,
        localIpv4Address = recorderWifiIpv4Address(network),
        releaseAction = releaseAction
    )

    private fun recorderWifiIpv4Address(network: Network): Inet4Address? {
        val fromNetwork = connectivity.getLinkProperties(network)
            ?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        if (fromNetwork != null) return fromNetwork

        // Some EMUI 11 builds do not expose LinkProperties for a manually
        // joined local-only Wi-Fi network. WifiInfo still exposes its IPv4.
        val legacyAddress = runCatching { wifiManager.connectionInfo?.ipAddress ?: 0 }.getOrDefault(0)
        if (legacyAddress == 0) return null
        val bytes = byteArrayOf(
            (legacyAddress and 0xFF).toByte(),
            ((legacyAddress shr 8) and 0xFF).toByte(),
            ((legacyAddress shr 16) and 0xFF).toByte(),
            ((legacyAddress shr 24) and 0xFF).toByte()
        )
        return InetAddress.getByAddress(bytes) as? Inet4Address
    }
}

private class RecorderWifiFailure(val code: String, message: String) : IllegalStateException("$code | $message")

class RecorderWifiLease(
    val network: Network,
    val localIpv4Address: Inet4Address?,
    private val releaseAction: () -> Unit
) : AutoCloseable {
    override fun close() = releaseAction()
}

private fun String?.normalizedSsid(): String? = this
    ?.trim('"')
    ?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
