package com.tonybenoy.smbdrop.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class FoundServer(val host: String, val name: String, val ip: String)

class Discovery(private val c: Context) {

    private fun localSubnet(): String? {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        val a = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return a.address.hostAddress?.substringBeforeLast('.')
    }

    private fun open445(ip: String, timeoutMs: Int = 300, port: Int = 445): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (e: Exception) { false }

    /** Accepts "host" or "host:port". */
    fun reachable(host: String): Boolean {
        val (h, p) = splitHost(host)
        return open445(h, 1500, p)
    }
    private fun splitHost(host: String): Pair<String, Int> =
        if (host.count { it == ':' } == 1 && host.substringAfter(':').all(Char::isDigit)) host.substringBefore(':') to host.substringAfter(':').toInt() else host to 445

    /** Resolves the server by its name (mDNS / DNS / NetBIOS-by-DNS) – used when the IP changed. */
    fun resolve(host: String): String? = try { InetAddress.getByName(splitHost(host).first).hostAddress } catch (e: Exception) { null }

    /** Streams servers as they are found: mDNS (_smb._tcp) plus a /24 port-445 sweep with 64 probes. */
    fun scan(): Flow<FoundServer> = channelFlow {
        val seen = HashSet<String>()
        suspend fun emit(s: FoundServer) { if (seen.add(s.ip)) send(s) }

        val wifi = c.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("smbdrop-scan").apply { setReferenceCounted(false); acquire() }
        val nsd = c.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            @Suppress("DEPRECATION")
            override fun onServiceFound(s: NsdServiceInfo) {
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val ip = i.host?.hostAddress ?: return
                        launch { emit(FoundServer(i.serviceName, i.serviceName, ip)) }
                    }
                })
            }
        }
        runCatching { nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }

        val prefix = localSubnet()
        if (prefix != null) {
            val sem = kotlinx.coroutines.sync.Semaphore(64)
            (1..254).map { i ->
                launch(Dispatchers.IO) {
                    sem.acquire()
                    try {
                        val ip = "$prefix.$i"
                        if (open445(ip)) {
                            val name = withTimeoutOrNull(1500) {
                                runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()
                            }?.takeIf { it != ip } ?: ip
                            val short = if (name == ip) ip else name.substringBefore('.')
                            emit(FoundServer(name, short, ip))
                        }
                    } finally { sem.release() }
                }
            }.joinAll()
        }
        delay(2500) // let late mDNS answers arrive
        runCatching { nsd.stopServiceDiscovery(listener) }
        lock.release()
        close()
        awaitClose { runCatching { lock.release() } }
    }.flowOn(Dispatchers.IO)

    /** Emits whenever the default network changes, to trigger re-discovery and queue wake-up. */
    fun networkChanges(): Flow<Unit> = callbackFlow {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
        }
        cm.registerDefaultNetworkCallback(cb)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }
}
