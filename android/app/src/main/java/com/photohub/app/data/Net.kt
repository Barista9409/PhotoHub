package com.photohub.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.photohub.app.data.api.PhotoHubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Picks the NAS address: the LAN URL when it answers (at home), otherwise the Tailscale URL
 * (spec F6 "自宅ではLAN、外出先ではTailscale経由を自動で切り替える").
 */
class Net(private val ctx: Context, private val prefs: Prefs) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Volatile private var cachedBase: String? = null
    @Volatile private var cachedAt = 0L

    fun client(token: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
        }
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(3, TimeUnit.SECONDS).build()

    /**
     * Whether the phone is really on mobile data. Looks past a VPN (Tailscale always on): Android
     * treats a VPN as metered, which would make "Wi-Fi only" work wait forever even on Wi-Fi.
     */
    @Suppress("DEPRECATION")
    fun isMetered(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val real = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        if (real.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }) return false
        if (real.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }) return true
        return cm.isActiveNetworkMetered
    }

    fun isOnline(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Wi-Fi SSID if readable (needs location permission); null otherwise. */
    @Suppress("DEPRECATION")
    fun currentSsid(): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val info = (caps.transportInfo as? WifiInfo) ?: ctx.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
        val ssid = info?.ssid?.trim('"') ?: return null
        return ssid.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    private fun answers(base: String): Boolean = try {
        probeClient.newCall(Request.Builder().url("$base/api/health").build()).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    suspend fun baseUrl(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val s = prefs.current()
        val now = System.currentTimeMillis()
        cachedBase?.let { if (!force && now - cachedAt < 60_000) return@withContext it }
        // At home (SSID matches, or SSID unknown) try the LAN first; away, go straight to Tailscale.
        val ssid = currentSsid()
        val away = s.homeSsid.isNotBlank() && ssid != null && ssid != s.homeSsid || (ssid == null && isMetered())
        val ordered = (if (away) listOf(s.remoteUrl, s.lanUrl) else listOf(s.lanUrl, s.remoteUrl))
            .filter { it.isNotBlank() }
        val found = ordered.firstOrNull { answers(it) }
        cachedBase = found
        cachedAt = now
        found
    }

    suspend fun api(force: Boolean = false): PhotoHubApi? {
        val base = baseUrl(force) ?: return null
        val token = prefs.current().token
        return Retrofit.Builder()
            .baseUrl("$base/")
            .client(client(token))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PhotoHubApi::class.java)
    }

    suspend fun isHomeLan(): Boolean {
        val s = prefs.current()
        if (s.homeSsid.isNotBlank()) return currentSsid() == s.homeSsid
        return s.lanUrl.isNotBlank() && withContext(Dispatchers.IO) { answers(s.lanUrl) }
    }
}
