package com.photohub.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Finds the PhotoHub server on the home Wi-Fi without typing an IP address:
 *  1. mDNS (_photohub._tcp), which the NAS announces
 *  2. if that is blocked, asks every address of this /24 network for /api/health
 */
object Discovery {
    private val probe = OkHttpClient.Builder().connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS).build()

    fun isPhotoHub(base: String): Boolean = try {
        probe.newCall(Request.Builder().url("$base/api/health").build()).execute().use {
            it.isSuccessful && it.body!!.string().contains("\"photohub\"")
        }
    } catch (e: Exception) { false }

    suspend fun find(ctx: Context): String? = withContext(Dispatchers.IO) {
        mdns(ctx)?.takeIf { isPhotoHub(it) } ?: scan(ctx)
    }

    private suspend fun mdns(ctx: Context): String? = withTimeoutOrNull(4000) {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        suspendCancellableCoroutine { cont ->
            var done = false
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { if (!done) { done = true; cont.resume(null) } }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(service: NsdServiceInfo) {}
                @Suppress("DEPRECATION")
                override fun onServiceFound(service: NsdServiceInfo) {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            if (!done) { done = true; cont.resume("http://$host:${info.port}") }
                        }
                    })
                }
            }
            nsd.discoverServices("_photohub._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
            cont.invokeOnCancellation { try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {} }
        }
    }

    private suspend fun scan(ctx: Context, port: Int = 8765): String? = coroutineScope {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return@coroutineScope null
        val caps = cm.getNetworkCapabilities(net) ?: return@coroutineScope null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return@coroutineScope null
        }
        val mine = cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress } ?: return@coroutineScope null
        val b = mine.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}"
        for (chunk in (1..254).chunked(64)) {
            val hits = chunk.map { i -> async(Dispatchers.IO) { "http://$prefix.$i:$port".takeIf { isPhotoHub(it) } } }.awaitAll()
            hits.firstOrNull { it != null }?.let { return@coroutineScope it }
        }
        null
    }
}
