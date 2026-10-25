package com.iamburny.waveband.logos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import com.iamburny.waveband.backend.DabServiceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * Station logos published by broadcasters through RadioDNS, cached on the device.
 *
 * [refresh] looks up the stations that have no logo yet, or whose logo is older than
 * [REFRESH_AFTER_MS], downloads one logo per station and stores it scaled to fit
 * [ServiceInformation.TARGET_SIZE]. Stations RadioDNS has no logo for are retried after
 * [RETRY_MISSING_AFTER_MS]; network failures are retried on the next refresh.
 */
class StationLogos(context: Context, scope: CoroutineScope) {

    private val dir = File(context.filesDir, "logos")
    private val indexFile = File(dir, "index.properties")
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val client = RadioDnsClient(connectivity)
    private val refreshLock = Mutex()
    private val enabled = MutableStateFlow(false)

    // Logo key -> time the lookup ran and whether it found a logo.
    private val index = HashMap<String, Entry>()
    private val stored = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val bitmaps = object : LruCache<String, Bitmap>(BITMAP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * Logo keys ([RadioDns.logoKey]) that have a stored logo, mapped to a version that changes
     * when the logo is replaced. Empty while logos are switched off.
     */
    val available: StateFlow<Map<String, Long>> =
        combine(enabled, stored) { on, logos -> if (on) logos else emptyMap() }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Whether the device has a network connection. */
    val online: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(connectivity.activeNetwork != null && connectivity.activeNetwork != network)
            }
        }
        trySend(connectivity.activeNetwork != null)
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    init {
        scope.launch(Dispatchers.IO) { refreshLock.withLock { loadIndex() } }
    }

    fun setEnabled(on: Boolean) {
        enabled.value = on
    }

    /** The logo for [key] at [version] if it is already decoded in memory. */
    fun cachedBitmap(key: String, version: Long): Bitmap? = bitmaps.get("$key@$version")

    /** The stored logo for [key] at [version], or null if it cannot be read. */
    suspend fun bitmap(key: String, version: Long): Bitmap? {
        val cacheKey = "$key@$version"
        bitmaps.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(file(key).path)?.also { bitmaps.put(cacheKey, it) }
        }
    }

    /** The stored logo for [key] as PNG bytes, or null if there is none. */
    suspend fun png(key: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { file(key).readBytes() }.getOrNull()
    }

    /** Looks up and downloads missing or outdated logos for [stations]. */
    suspend fun refresh(stations: List<DabServiceInfo>) = refreshLock.withLock {
        withContext(Dispatchers.IO) { refreshLocked(stations) }
    }

    private suspend fun refreshLocked(stations: List<DabServiceInfo>) {
        val now = System.currentTimeMillis()
        val due = stations
            .mapNotNull { info -> RadioDns.logoKey(info)?.let { it to info } }
            .distinctBy { it.first }
            .filter { (key, _) ->
                val entry = index[key]
                // A check time in the future comes from a clock that was wrong then or is wrong now.
                entry == null || entry.checkedAt > now || now - entry.checkedAt > if (entry.found) REFRESH_AFTER_MS else RETRY_MISSING_AFTER_MS
            }
        if (due.isEmpty()) return
        dir.mkdirs()
        val urls = HashMap<String, String>()
        val files = HashMap<String, Result<ServiceInformation>>()
        var networkFailures = 0
        for ((key, info) in due) {
            val found = try {
                fetch(info, key, urls, files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RadioDnsClient.NotFound) {
                false
            } catch (e: IOException) {
                // Connection problems: unreachable hosts, timeouts, DNS failures.
                Log.i(TAG, "Logo lookup for ${info.label} failed: ${e.message}")
                // Without a working connection the rest would fail the same way.
                if (++networkFailures >= MAX_NETWORK_FAILURES) break
                continue
            } catch (e: Exception) {
                Log.w(TAG, "Unusable logo data for ${info.label}", e)
                false
            }
            networkFailures = 0
            index[key] = Entry(now, found)
            if (!found && file(key).delete()) stored.update { it - key }
            saveIndex()
        }
    }

    /** Fetches the logo of [info]; returns false when the broadcaster publishes none. */
    private suspend fun fetch(
        info: DabServiceInfo,
        key: String,
        urls: MutableMap<String, String>,
        files: MutableMap<String, Result<ServiceInformation>>,
    ): Boolean {
        val lookupName = RadioDns.lookupName(info) ?: return false
        val bearer = RadioDns.bearerUri(info) ?: return false
        val domain = client.broadcasterDomain(lookupName)
        val url = urls[domain] ?: client.serviceInformationUrl(domain).also { urls[domain] = it }
        // A broadcaster's SI file lists all its stations, so it is downloaded once per refresh.
        val si = files.getOrPut(url) {
            runCatching { client.download(url, MAX_SI_BYTES, ServiceInformation::parse) }
                .onFailure { if (it is CancellationException) throw it }
        }.getOrThrow()
        val logo = si.find(bearer)?.let { ServiceInformation.bestLogo(it.logos) } ?: return false
        val bytes = client.download(logo.url, MAX_IMAGE_BYTES) { it.readBytes() }
        val bitmap = decodeScaled(bytes) ?: return false
        val target = file(key)
        val temp = File(dir, "$key.tmp")
        temp.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("Could not store the logo for ${info.label}")
        }
        stored.update { it + (key to target.lastModified()) }
        return true
    }

    /** Decodes [bytes] scaled down to fit [ServiceInformation.TARGET_SIZE] square. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val target = ServiceInformation.TARGET_SIZE
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= target) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(target.toFloat() / decoded.width, target.toFloat() / decoded.height)
        if (scale >= 1f) return decoded
        val scaled = decoded.scale((decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1))
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun file(key: String) = File(dir, "$key.png")

    private fun loadIndex() {
        val properties = Properties()
        runCatching { indexFile.inputStream().use(properties::load) }
        val logos = HashMap<String, Long>()
        for (key in properties.stringPropertyNames()) {
            val parts = properties.getProperty(key).split(',')
            val checkedAt = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val found = parts.getOrNull(1) == "found"
            val f = file(key)
            // A logo whose file has gone is looked up again on the next refresh.
            if (found && !f.exists()) continue
            index[key] = Entry(checkedAt, found)
            if (found) logos[key] = f.lastModified()
        }
        stored.value = logos
    }

    private fun saveIndex() {
        val properties = Properties()
        for ((key, entry) in index) properties.setProperty(key, "${entry.checkedAt},${if (entry.found) "found" else "none"}")
        val temp = File(dir, "index.tmp")
        runCatching {
            temp.outputStream().use { properties.store(it, null) }
            if (!temp.renameTo(indexFile)) temp.delete()
        }.onFailure { Log.w(TAG, "Could not save the logo index", it) }
    }

    private data class Entry(val checkedAt: Long, val found: Boolean)

    private companion object {
        const val TAG = "StationLogos"
        // Broadcasters ask for logos to be refreshed at least monthly.
        const val REFRESH_AFTER_MS = 25L * 24 * 60 * 60 * 1000
        const val RETRY_MISSING_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_NETWORK_FAILURES = 3
        const val MAX_SI_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val BITMAP_CACHE_BYTES = 24 * 1024 * 1024
    }
}
