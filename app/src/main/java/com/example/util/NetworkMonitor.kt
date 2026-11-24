package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.channels.ClosedByInterruptException
import javax.net.ssl.SSLException

object NetworkMonitor {

    private const val DEFAULT_UNREACHABLE_COOLDOWN_MS = 15_000L
    private const val VERIFY_COALESCE_INTERVAL_MS = 3_000L
    private const val IMMEDIATE_PROBE_COALESCE_MS = 600L
    private val PROGRESSIVE_RECOVERY_DELAYS_MS = longArrayOf(750L, 1_500L, 3_000L, 6_000L, 12_000L)

    private val UNREACHABLE_HTTP_CODES = setOf(
        502, 503, 504,
        520, 521, 522, 523, 524, 525, 526,
        530
    )

    private val stateLock = Any()

    @Volatile
    private var cachedHasTransport: Boolean = true

    @Volatile
    private var callbackRegistered: Boolean = false

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var serverUnreachableFlag: Boolean = false

    @Volatile
    private var serverUnreachableUntilMs: Long = 0L

    @Volatile
    private var serverUnreachableUrl: String = ""

    @Volatile
    private var activeServerUrl: String = ""

    @Volatile
    private var isProbingRecovery: Boolean = false

    @Volatile
    private var serverRecoveryProbe: (suspend (String) -> Boolean)? = null

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var recoveryJob: Job? = null

    @Volatile
    private var verifyJob: Job? = null

    @Volatile
    private var transportLostJob: Job? = null

    @Volatile
    private var lastVerifiedHealthyAtMs: Long = 0L

    @Volatile
    private var lastProbeStartedAtMs: Long = 0L

    @Volatile
    private var recoveryAttemptCount: Int = 0

    @Volatile
    private var cachedNetworkValidated: Boolean = false

    private val _networkState = MutableStateFlow(true)

    /**
     * Registers the server reachability health probe used to recover automatically
     * without UI flickering once the server comes back online.
     */
    fun setServerRecoveryProbe(probe: (suspend (String) -> Boolean)?) {
        synchronized(stateLock) {
            serverRecoveryProbe = probe
        }
    }

    /**
     * Pure device transport check (Wi-Fi / Cellular / Ethernet / VPN).
     * Does NOT check whether the Alaktra backend server is reachable.
     */
    fun hasNetworkTransport(context: Context): Boolean {
        if (!initialized) {
            ensureInitialized(context)
        }
        return synchronized(stateLock) {
            if (callbackRegistered) {
                cachedHasTransport
            } else {
                queryConnectivity(context).also {
                    cachedHasTransport = it
                    recomputeUnifiedStateLocked()
                }
            }
        }
    }

    /**
     * Unified online check: returns true ONLY when the device has an active network
     * transport AND the active server is not currently unreachable.
     */
    fun isOnline(context: Context): Boolean {
        val hasTransport = hasNetworkTransport(context)
        return synchronized(stateLock) {
            val unified = hasTransport && !isServerTemporarilyUnreachableLocked(activeServerUrl)
            if (_networkState.value != unified) {
                _networkState.value = unified
            }
            unified
        }
    }

    /**
     * Observes the unified online state (device transport ON && server reachable).
     */
    fun observeNetwork(context: Context): Flow<Boolean> {
        ensureInitialized(context)
        synchronized(stateLock) {
            recomputeUnifiedStateLocked()
        }
        return _networkState.asStateFlow()
    }

    fun isUnreachableHttpStatus(code: Int): Boolean = code in UNREACHABLE_HTTP_CODES

    fun matchesActiveServer(url: String): Boolean {
        val active = activeServerUrl.trim()
        if (active.isEmpty()) return true
        return runCatching {
            val activeHost = URI(active).host?.lowercase()
            val candidateHost = URI(url.trim()).host?.lowercase()
            if (activeHost.isNullOrBlank() || candidateHost.isNullOrBlank()) {
                true
            } else {
                activeHost == candidateHost
            }
        }.getOrDefault(true)
    }

    fun updateActiveServerUrl(url: String) {
        val trimmed = url.trim().trimEnd('/')
        synchronized(stateLock) {
            if (trimmed != activeServerUrl) {
                activeServerUrl = trimmed
                markServerReachableLocked()
            }
        }
    }

    fun markServerUnreachable(
        serverUrl: String = activeServerUrl,
        cooldownMs: Long = DEFAULT_UNREACHABLE_COOLDOWN_MS
    ) {
        val effectiveCooldown = cooldownMs.coerceAtLeast(1_000L)
        synchronized(stateLock) {
            val trimmed = serverUrl.trim().trimEnd('/')
            if (trimmed.isNotEmpty()) {
                activeServerUrl = trimmed
                serverUnreachableUrl = trimmed
            } else {
                serverUnreachableUrl = activeServerUrl
            }
            serverUnreachableFlag = true
            isProbingRecovery = false
            verifyJob?.cancel()
            verifyJob = null
            recoveryAttemptCount = 0
            serverUnreachableUntilMs = System.currentTimeMillis() + effectiveCooldown
            recomputeUnifiedStateLocked()
            if (cachedHasTransport) {
                val existingJob = recoveryJob
                val reschedule = existingJob == null || !existingJob.isActive
                if (reschedule) {
                    val initialDelay = if (cooldownMs == DEFAULT_UNREACHABLE_COOLDOWN_MS) {
                        PROGRESSIVE_RECOVERY_DELAYS_MS[0]
                    } else {
                        effectiveCooldown
                    }
                    scheduleRecoveryCheckLocked(initialDelay)
                }
            }
        }
    }

    /**
     * Reports a network or HTTP error without causing grey/normal UI flickering on cancelled
     * calls or isolated transient slowdowns while the server is actually healthy.
     */
    fun reportSuspectedServerFailure(
        context: Context,
        serverUrl: String = activeServerUrl,
        error: Throwable? = null,
        httpCode: Int? = null,
        verifyFirst: Boolean = false
    ) {
        if (!hasNetworkTransport(context)) return
        val resolvedUrl = serverUrl.trim().trimEnd('/').ifEmpty { activeServerUrl }
        if (resolvedUrl.isNotEmpty() && !matchesActiveServer(resolvedUrl)) return
        if (isCancellationOrAborted(error)) return

        val probe = synchronized(stateLock) { serverRecoveryProbe }
        if (httpCode != null) {
            if (!isUnreachableHttpStatus(httpCode)) {
                return
            }
            if (!verifyFirst || probe == null) {
                markServerUnreachable(resolvedUrl)
                return
            }
        } else if (!verifyFirst && isDefinitiveConnectionFailure(error)) {
            markServerUnreachable(resolvedUrl)
            return
        }

        if (probe == null) {
            markServerUnreachable(resolvedUrl)
            return
        }

        synchronized(stateLock) {
            if (serverUnreachableFlag || isProbingRecovery) return
            if (verifyJob?.isActive == true) return
            val now = System.currentTimeMillis()
            if (verifyFirst && httpCode == null && now - lastVerifiedHealthyAtMs < VERIFY_COALESCE_INTERVAL_MS) {
                return
            }
            val targetSnapshot = resolvedUrl.ifEmpty { activeServerUrl }
            verifyJob = monitorScope.launch {
                val reachable = try {
                    probe(targetSnapshot)
                } catch (_: Throwable) {
                    false
                }
                synchronized(stateLock) {
                    if (targetSnapshot != activeServerUrl && activeServerUrl.isNotEmpty()) {
                        return@launch
                    }
                    if (reachable) {
                        lastVerifiedHealthyAtMs = System.currentTimeMillis()
                    } else if (cachedHasTransport) {
                        recoveryAttemptCount = 0
                        serverUnreachableUrl = targetSnapshot.ifEmpty { activeServerUrl }
                        serverUnreachableFlag = true
                        isProbingRecovery = false
                        serverUnreachableUntilMs = System.currentTimeMillis() + DEFAULT_UNREACHABLE_COOLDOWN_MS
                        recomputeUnifiedStateLocked()
                        if (recoveryJob?.isActive != true) {
                            scheduleRecoveryCheckLocked(PROGRESSIVE_RECOVERY_DELAYS_MS[0])
                        }
                    }
                }
            }
        }
    }

    fun markServerReachable() {
        synchronized(stateLock) {
            markServerReachableLocked()
        }
    }

    private fun markServerReachableLocked() {
        recoveryJob?.cancel()
        recoveryJob = null
        verifyJob?.cancel()
        verifyJob = null
        isProbingRecovery = false
        recoveryAttemptCount = 0
        serverUnreachableFlag = false
        serverUnreachableUntilMs = 0L
        serverUnreachableUrl = ""
        lastVerifiedHealthyAtMs = System.currentTimeMillis()
        recomputeUnifiedStateLocked()
    }

    /**
     * Triggers a coalesced immediate /health recovery probe if the device has transport
     * and the server was previously marked unreachable (e.g. app foregrounded or user refreshed).
     */
    fun verifyReachabilitySoon(context: Context, minIntervalMs: Long = 900L) {
        if (!hasNetworkTransport(context)) return
        synchronized(stateLock) {
            if (!cachedHasTransport || !serverUnreachableFlag) return
            if (isProbingRecovery) return
            val now = System.currentTimeMillis()
            if (now - lastProbeStartedAtMs < minIntervalMs) return
            if (serverRecoveryProbe != null) {
                recoveryAttemptCount = 0
                scheduleRecoveryCheckLocked(0L, forceReschedule = true)
            }
        }
    }

    fun isServerTemporarilyUnreachable(serverUrl: String = activeServerUrl): Boolean {
        synchronized(stateLock) {
            return isServerTemporarilyUnreachableLocked(serverUrl)
        }
    }

    private fun isServerTemporarilyUnreachableLocked(serverUrl: String): Boolean {
        val target = serverUrl.trim().trimEnd('/').ifEmpty { activeServerUrl }
        if (target.isNotEmpty() && serverUnreachableUrl.isNotEmpty() && target != serverUnreachableUrl) {
            return false
        }
        if (isProbingRecovery) return true
        if (!serverUnreachableFlag) return false

        // When a health probe is registered, never auto-flip back to reachable purely on timer
        // expiry — stay cleanly offline until the probe confirms /health or a real response arrives.
        if (serverRecoveryProbe != null) {
            return true
        }

        val until = serverUnreachableUntilMs
        if (until <= 0L) {
            serverUnreachableFlag = false
            return false
        }
        val now = System.currentTimeMillis()
        if (now >= until) {
            serverUnreachableFlag = false
            serverUnreachableUntilMs = 0L
            recomputeUnifiedStateLocked()
            return false
        }
        return true
    }

    fun shouldSkipNetworkRequests(context: Context, forceNetwork: Boolean = false): Boolean {
        if (!hasNetworkTransport(context)) return true
        return !forceNetwork && isServerTemporarilyUnreachable()
    }

    private fun recomputeUnifiedStateLocked() {
        val serverUnreachable = isServerTemporarilyUnreachableLocked(activeServerUrl)
        val unified = cachedHasTransport && !serverUnreachable
        if (_networkState.value != unified) {
            _networkState.value = unified
        }
    }

    private fun nextProgressiveRecoveryDelayMs(): Long {
        val idx = recoveryAttemptCount.coerceIn(0, PROGRESSIVE_RECOVERY_DELAYS_MS.lastIndex)
        return PROGRESSIVE_RECOVERY_DELAYS_MS[idx]
    }

    private fun scheduleRecoveryCheckLocked(cooldownMs: Long, forceReschedule: Boolean = true) {
        if (!forceReschedule && recoveryJob?.isActive == true) return
        recoveryJob?.cancel()
        val probe = serverRecoveryProbe
        val urlSnapshot = activeServerUrl
        recoveryJob = monitorScope.launch {
            if (cooldownMs > 0L) {
                delay(cooldownMs)
            }
            val shouldContinue = synchronized(stateLock) {
                cachedHasTransport &&
                    urlSnapshot == activeServerUrl &&
                    serverUnreachableFlag
            }
            if (!shouldContinue) {
                return@launch
            }
            if (probe != null) {
                synchronized(stateLock) {
                    isProbingRecovery = true
                    lastProbeStartedAtMs = System.currentTimeMillis()
                }
                val reachable = try {
                    probe(urlSnapshot)
                } catch (_: Throwable) {
                    false
                }
                synchronized(stateLock) {
                    isProbingRecovery = false
                    if (urlSnapshot != activeServerUrl) {
                        recomputeUnifiedStateLocked()
                        return@launch
                    }
                    if (reachable) {
                        markServerReachableLocked()
                    } else if (cachedHasTransport && serverUnreachableFlag) {
                        recoveryAttemptCount = (recoveryAttemptCount + 1).coerceAtMost(PROGRESSIVE_RECOVERY_DELAYS_MS.lastIndex)
                        val nextDelayMs = nextProgressiveRecoveryDelayMs()
                        serverUnreachableUrl = urlSnapshot
                        serverUnreachableUntilMs = System.currentTimeMillis() + nextDelayMs
                        recomputeUnifiedStateLocked()
                        scheduleRecoveryCheckLocked(nextDelayMs, forceReschedule = true)
                    } else {
                        recomputeUnifiedStateLocked()
                    }
                }
            } else {
                synchronized(stateLock) {
                    serverUnreachableFlag = false
                    serverUnreachableUntilMs = 0L
                    recomputeUnifiedStateLocked()
                }
            }
        }
    }

    private fun isCancellationOrAborted(error: Throwable?): Boolean {
        var cur = error
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur is CancellationException ||
                cur is ClosedByInterruptException ||
                cur is InterruptedException
            ) {
                return true
            }
            if (cur is InterruptedIOException && cur !is SocketTimeoutException) {
                return true
            }
            val msg = cur.message.orEmpty()
            if (msg.contains("Canceled", ignoreCase = true) ||
                msg.contains("Cancelled", ignoreCase = true) ||
                cur.javaClass.simpleName.contains("StreamReset", ignoreCase = true)
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun isDefinitiveConnectionFailure(error: Throwable?): Boolean {
        var cur = error
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur is ConnectException ||
                cur is UnknownHostException ||
                cur is NoRouteToHostException ||
                cur is PortUnreachableException ||
                cur is SSLException
            ) {
                return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    private fun ensureInitialized(context: Context) {
        if (initialized && callbackRegistered) return
        synchronized(this) {
            if (initialized && callbackRegistered) return
            val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
            val initial = queryConnectivity(appCtx)
            synchronized(stateLock) {
                cachedHasTransport = initial
                recomputeUnifiedStateLocked()
            }

            val cm = try {
                appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            } catch (_: Throwable) {
                null
            }

            if (cm != null && !callbackRegistered) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        synchronized(stateLock) {
                            transportLostJob?.cancel()
                            transportLostJob = null
                            val wasTransportOff = !cachedHasTransport
                            cachedHasTransport = true
                            val validatedNow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                try {
                                    cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                                } catch (_: Throwable) {
                                    false
                                }
                            } else {
                                true
                            }
                            cachedNetworkValidated = validatedNow
                            if (serverUnreachableFlag) {
                                // Never flash UI back to online before probing /health!
                                recomputeUnifiedStateLocked()
                                if (serverRecoveryProbe != null) {
                                    val now = System.currentTimeMillis()
                                    val shouldProbeNow = !isProbingRecovery && (
                                        wasTransportOff || (now - lastProbeStartedAtMs) >= IMMEDIATE_PROBE_COALESCE_MS
                                        )
                                    if (shouldProbeNow) {
                                        recoveryAttemptCount = 0
                                        scheduleRecoveryCheckLocked(0L, forceReschedule = true)
                                    } else if (recoveryJob?.isActive != true) {
                                        scheduleRecoveryCheckLocked(PROGRESSIVE_RECOVERY_DELAYS_MS[0], forceReschedule = false)
                                    }
                                } else if (wasTransportOff) {
                                    markServerReachableLocked()
                                }
                            } else {
                                recomputeUnifiedStateLocked()
                            }
                        }
                    }

                    override fun onLost(network: Network) {
                        val stillHasTransport = queryConnectivity(appCtx)
                        synchronized(stateLock) {
                            if (stillHasTransport) {
                                transportLostJob?.cancel()
                                transportLostJob = null
                                cachedHasTransport = true
                                recomputeUnifiedStateLocked()
                            } else if (transportLostJob?.isActive != true) {
                                // Settle briefly (650ms) so Wi-Fi <-> Cellular handovers don't flicker grey
                                transportLostJob = monitorScope.launch {
                                    delay(650L)
                                    val confirmedStillHasTransport = queryConnectivity(appCtx)
                                    synchronized(stateLock) {
                                        transportLostJob = null
                                        cachedHasTransport = confirmedStillHasTransport
                                        recomputeUnifiedStateLocked()
                                    }
                                }
                            }
                        }
                    }

                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val active = try { cm.activeNetwork } catch (_: Throwable) { null }
                            if (active != null && active != network) {
                                return
                            }
                        }
                        val hasTransport = hasConnectivity(networkCapabilities)
                        val isValidated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        } else {
                            hasTransport
                        }
                        synchronized(stateLock) {
                            if (hasTransport) {
                                transportLostJob?.cancel()
                                transportLostJob = null
                            }
                            val wasTransportOff = !cachedHasTransport
                            val becameValidated = isValidated && !cachedNetworkValidated
                            cachedHasTransport = hasTransport
                            cachedNetworkValidated = hasTransport && isValidated
                            if (hasTransport && serverUnreachableFlag) {
                                recomputeUnifiedStateLocked()
                                if (serverRecoveryProbe != null) {
                                    val now = System.currentTimeMillis()
                                    val shouldProbeNow = !isProbingRecovery && (
                                        wasTransportOff ||
                                            becameValidated ||
                                            (recoveryAttemptCount == 0 && (now - lastProbeStartedAtMs) >= IMMEDIATE_PROBE_COALESCE_MS)
                                        )
                                    if (shouldProbeNow) {
                                        recoveryAttemptCount = 0
                                        scheduleRecoveryCheckLocked(0L, forceReschedule = true)
                                    } else if (recoveryJob?.isActive != true) {
                                        scheduleRecoveryCheckLocked(PROGRESSIVE_RECOVERY_DELAYS_MS[0], forceReschedule = false)
                                    }
                                } else if (wasTransportOff) {
                                    markServerReachableLocked()
                                }
                            } else {
                                recomputeUnifiedStateLocked()
                            }
                        }
                    }
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        cm.registerDefaultNetworkCallback(callback)
                        callbackRegistered = true
                    } else {
                        val request = NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build()
                        cm.registerNetworkCallback(request, callback)
                        callbackRegistered = true
                    }
                } catch (_: Throwable) {
                    try {
                        val request = NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build()
                        cm.registerNetworkCallback(request, callback)
                        callbackRegistered = true
                    } catch (_: Throwable) {
                        callbackRegistered = false
                    }
                }
            }
            initialized = true
        }
    }

    private fun hasConnectivity(caps: NetworkCapabilities): Boolean {
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun queryConnectivity(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                hasConnectivity(caps)
            } else {
                @Suppress("DEPRECATION")
                val activeNetworkInfo = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                activeNetworkInfo != null && activeNetworkInfo.isConnected
            }
        } catch (_: Throwable) {
            true
        }
    }
}
