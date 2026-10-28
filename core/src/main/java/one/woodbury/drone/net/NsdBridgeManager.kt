package one.woodbury.drone.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "NsdBridgeManager"

/**
 * Publishes the Bridge service over mDNS / DNS-SD using [NsdManager] so viewer
 * clients on the local Wi-Fi network can discover it without manual IP entry.
 */
class NsdBridgeServer(
    context: Context,
    private val serviceName: String = UdpBridgeProtocol.NSD_SERVICE_NAME,
    private val serviceType: String = UdpBridgeProtocol.NSD_SERVICE_TYPE,
    private val port: Int = UdpBridgeProtocol.UDP_BRIDGE_PORT,
    private val onRegistrationChanged: (isRegistered: Boolean, serviceName: String?) -> Unit = { _, _ -> },
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val isRegistered = AtomicBoolean(false)
    private var registrationListener: NsdManager.RegistrationListener? = null

    /**
     * Registers the bridge service with [NsdManager].
     */
    @Synchronized
    fun register() {
        if (isRegistered.get() || nsdManager == null) return

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = this@NsdBridgeServer.serviceName
            this.serviceType = this@NsdBridgeServer.serviceType
            this.port = this@NsdBridgeServer.port
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredServiceInfo: NsdServiceInfo) {
                isRegistered.set(true)
                Log.i(TAG, "NSD service registered: ${registeredServiceInfo.serviceName}")
                onRegistrationChanged(true, registeredServiceInfo.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered.set(false)
                Log.e(TAG, "NSD service registration failed with error code: $errorCode")
                onRegistrationChanged(false, null)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered.set(false)
                Log.i(TAG, "NSD service unregistered: ${serviceInfo.serviceName}")
                onRegistrationChanged(false, null)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD service unregistration failed with error code: $errorCode")
            }
        }
        registrationListener = listener

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to register NSD service.", exception)
            onRegistrationChanged(false, null)
        }
    }

    /**
     * Unregisters the bridge service.
     */
    @Synchronized
    fun unregister() {
        val listener = registrationListener
        registrationListener = null
        if (listener != null && nsdManager != null && isRegistered.getAndSet(false)) {
            try {
                nsdManager.unregisterService(listener)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to unregister NSD service.", exception)
            }
        }
        onRegistrationChanged(false, null)
    }
}

/**
 * Discovers and resolves Bridge services on the local network via [NsdManager].
 *
 * Resolves discovered services sequentially to avoid `NsdManager.FAILURE_ALREADY_ACTIVE`
 * errors on Android platforms that only support a single concurrent resolve operation.
 */
class NsdBridgeClient(
    context: Context,
    private val serviceType: String = UdpBridgeProtocol.NSD_SERVICE_TYPE,
    private val onDeviceResolved: (hostAddress: String, port: Int) -> Unit,
    private val onStatusChanged: (status: String) -> Unit = {},
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val isDiscovering = AtomicBoolean(false)
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val isResolving = AtomicBoolean(false)

    /**
     * Starts discovering bridge services.
     */
    @Synchronized
    fun startDiscovery() {
        if (isDiscovering.get() || nsdManager == null) return

        val cleanServiceType = if (serviceType.endsWith(".")) serviceType else "$serviceType."

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscovering.set(true)
                Log.i(TAG, "NSD discovery started for $regType.")
                onStatusChanged("Searching via mDNS ($regType)")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "NSD service candidate found: ${service.serviceName} (${service.serviceType})")
                val foundType = service.serviceType
                val expectedClean = cleanServiceType.trimEnd('.')
                if (foundType != null && foundType.contains(expectedClean)) {
                    enqueueResolve(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.w(TAG, "NSD service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering.set(false)
                Log.i(TAG, "NSD discovery stopped for $serviceType.")
                onStatusChanged("Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering.set(false)
                Log.e(TAG, "NSD start discovery failed with error code: $errorCode.")
                onStatusChanged("Discovery start failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "NSD stop discovery failed with error code: $errorCode.")
            }
        }
        discoveryListener = listener

        try {
            nsdManager.discoverServices(cleanServiceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to start NSD discovery.", exception)
            onStatusChanged("Discovery failed: ${exception.message}")
        }
    }

    /**
     * Stops discovering bridge services and clears any pending resolutions.
     */
    @Synchronized
    fun stopDiscovery() {
        resolveQueue.clear()
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null && nsdManager != null && isDiscovering.getAndSet(false)) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to stop NSD discovery.", exception)
            }
        }
    }

    /**
     * Stops and immediately restarts discovery, clearing any pending resolutions.
     */
    @Synchronized
    fun restartDiscovery() {
        stopDiscovery()
        startDiscovery()
    }

    private fun enqueueResolve(service: NsdServiceInfo) {
        resolveQueue.offer(service)
        processNextResolve()
    }

    private fun processNextResolve() {
        if (!isResolving.compareAndSet(false, true)) {
            return
        }

        val nextService = resolveQueue.poll()
        if (nextService == null) {
            isResolving.set(false)
            return
        }

        val manager = nsdManager
        if (manager == null) {
            isResolving.set(false)
            return
        }

        try {
            @Suppress("DEPRECATION")
            manager.resolveService(nextService, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                    try {
                        @Suppress("DEPRECATION")
                        val host = resolvedService.host?.hostAddress
                        val port = resolvedService.port
                        Log.i(TAG, "NSD service resolved: $host:$port (${resolvedService.serviceName})")
                        if (!host.isNullOrBlank()) {
                            onStatusChanged("Discovered $host via mDNS")
                            onDeviceResolved(host, port)
                        }
                    } finally {
                        isResolving.set(false)
                        processNextResolve()
                    }
                }

                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "NSD resolve failed for ${serviceInfo.serviceName} with error code: $errorCode.")
                    isResolving.set(false)
                    processNextResolve()
                }
            })
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to invoke resolveService for ${nextService.serviceName}.", exception)
            isResolving.set(false)
            processNextResolve()
        }
    }
}
