package com.iamburny.waveband.logos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import com.iamburny.waveband.backend.DabServiceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * [refresh] looks up the stations that are due and stores one logo per station, scaled to fit
 * [ServiceInformation.TARGET_SIZE]. A station is due when it has never been looked up, when its
 * logo is older than [REFRESH_AFTER_MS], [RETRY_NONE_AFTER_MS] after RadioDNS or the broadcaster
 * said it has no logo, or [RETRY_FAILED_AFTER_MS] after a broadcaster's server failed. A failed
 * lookup keeps the logo already stored. When RadioDNS itself cannot be reached the device is
 * treated as offline: the refresh stops and the remaining stations stay due.
 */
class StationLogos(context: Context, scope: CoroutineScope) {

    private val dir = File(context.filesDir, "logos")
    private val indexFile = File(dir, "index.properties")
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val client = RadioDnsClient(connectivity)
    private val refreshLock = Mutex()
    private val enabled = MutableStateFlow(false)

    // Logo key -> when it was last looked up and how that went.
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

    /**
     * Whether the default network offers internet access. It is not required to be validated:
     * some head units' connectivity checks never pass although the internet is reachable. A
     * connection without working internet makes RadioDNS unreachable, which stops a refresh.
     */
    val online: Flow<Boolean> = callbackFlow {
        fun hasInternet(network: Network?) =
            network?.let(connectivity::getNetworkCapabilities)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        val callback = object : ConnectivityManager.NetworkCallback() {
            // Before Android 8 a new default network is not always followed by a capabilities callback.
            override fun onAvailable(network: Network) {
                trySend(hasInternet(network))
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }

            override fun onLost(network: Network) {
                val next = connectivity.activeNetwork?.takeIf { it != network }
                trySend(hasInternet(next))
            }
        }
        trySend(hasInternet(connectivity.activeNetwork))
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

    /** Looks up and downloads the due logos of [stations]. */
    suspend fun refresh(stations: List<DabServiceInfo>) = refreshLock.withLock {
        withContext(Dispatchers.IO) { refreshLocked(stations) }
    }

    private suspend fun refreshLocked(stations: List<DabServiceInfo>) {
        val now = System.currentTimeMillis()
        // A station on several multiplexes shares one logo; each multiplex is a separate way to look it up.
        val due = stations
            .mapNotNull { info -> RadioDns.logoKey(info)?.let { it to info } }
            .groupBy({ it.first }, { it.second })
            .filterKeys { key -> index[key]?.isDue(now) ?: true }
        if (due.isEmpty()) return
        dir.mkdirs()
        val lookup = Lookup()
        var offlineFailures = 0
        for ((key, carriers) in due) {
            val outcome = try {
                lookup.logo(key, carriers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Offline) {
                Log.i(TAG, "RadioDNS unreachable: ${e.cause?.message}")
                // Every remaining lookup starts with RadioDNS, so they would fail the same way.
                if (++offlineFailures >= MAX_OFFLINE_FAILURES) break
                continue
            } catch (e: RadioDnsClient.NotOffered) {
                Outcome.WITHHELD
            } catch (e: RadioDnsClient.NotFound) {
                Outcome.NONE
            } catch (e: Exception) {
                // Cancelling closes open downloads, which then fail; that is not the server's fault.
                currentCoroutineContext().ensureActive()
                Log.i(TAG, "Logo lookup for ${carriers.first().label} failed: $e")
                Outcome.FAILED
            }
            offlineFailures = 0
            index[key] = Entry(now, outcome)
            if (outcome == Outcome.NONE && file(key).delete()) stored.update { it - key }
            saveIndex()
        }
    }

    /** Thrown when RadioDNS cannot be reached at all. */
    private class Offline(cause: IOException) : Exception(cause)

    /** One refresh's lookups, sharing each broadcaster's SPI server and SI file between its stations. */
    private inner class Lookup {
        private val urls = HashMap<String, Result<String>>()
        private val files = HashMap<String, Result<ServiceInformation>>()

        /** Stores the logo for [key], trying each of its [carriers] until RadioDNS knows one. */
        suspend fun logo(key: String, carriers: List<DabServiceInfo>): Outcome {
            var notFound: RadioDnsClient.NotFound? = null
            for (info in carriers) {
                val lookupName = RadioDns.lookupName(info) ?: continue
                val bearer = RadioDns.bearerUri(info) ?: continue
                val domain = try {
                    client.broadcasterDomain(lookupName)
                } catch (e: RadioDnsClient.NotFound) {
                    notFound = e
                    continue
                } catch (e: IOException) {
                    throw Offline(e)
                }
                val url = urls.getOrPut(domain) { attempt { client.serviceInformationUrl(domain) } }.getOrThrow()
                val si = files.getOrPut(url) { attempt { client.download(url, MAX_SI_BYTES, ServiceInformation::parse) } }.getOrThrow()
                val logo = si.find(bearer)?.let { ServiceInformation.bestLogo(it.logos) } ?: return Outcome.NONE
                val bytes = client.download(logo.url, MAX_IMAGE_BYTES) { it.readBytes() }
                val bitmap = decodeScaled(bytes) ?: throw IOException("Unreadable image at ${logo.url}")
                store(key, bitmap)
                return Outcome.FOUND
            }
            throw notFound ?: RadioDnsClient.NotFound("No RadioDNS name for $key")
        }

        // Caches failures too, so the stations of a failing broadcaster do not each wait for it.
        private inline fun <T> attempt(block: () -> T): Result<T> =
            runCatching(block).onFailure { if (it is CancellationException) throw it }
    }

    private fun store(key: String, bitmap: Bitmap) {
        val target = file(key)
        val temp = File(dir, "$key.tmp")
        try {
            temp.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        } finally {
            bitmap.recycle()
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not store the logo for $key")
        }
        stored.update { it + (key to target.lastModified()) }
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
        // Lookups that found nothing under an older lookup procedure are tried again.
        val sameLookup = properties.getProperty(LOOKUP_VERSION_KEY) == LOOKUP_VERSION
        for (key in properties.stringPropertyNames()) {
            if (key == LOOKUP_VERSION_KEY) continue
            val parts = properties.getProperty(key).split(',')
            val checkedAt = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val outcome = Outcome.entries.firstOrNull { it.stored == parts.getOrNull(1) } ?: continue
            val f = file(key)
            // A logo whose file has gone is looked up again on the next refresh.
            if (outcome == Outcome.FOUND && !f.exists()) continue
            // Kept with a check time of 0, so it is due at once but stays in the index (and its
            // logo, if any, stays visible) until it is looked up again.
            val due = outcome != Outcome.FOUND && !sameLookup
            index[key] = Entry(if (due) 0L else checkedAt, outcome)
            if (outcome != Outcome.NONE && f.exists()) logos[key] = f.lastModified()
        }
        stored.value = logos
    }

    private fun saveIndex() {
        val properties = Properties()
        properties.setProperty(LOOKUP_VERSION_KEY, LOOKUP_VERSION)
        for ((key, entry) in index) properties.setProperty(key, "${entry.checkedAt},${entry.outcome.stored}")
        val temp = File(dir, "index.tmp")
        runCatching {
            temp.outputStream().use { properties.store(it, null) }
            if (!temp.renameTo(indexFile)) temp.delete()
        }.onFailure { Log.w(TAG, "Could not save the logo index", it) }
    }

    /**
     * How a lookup went: a logo stored, none published, the server refused to serve this app, or
     * the server failed. Only NONE removes a logo stored earlier.
     */
    private enum class Outcome(val stored: String, val retryAfterMs: Long) {
        FOUND("found", REFRESH_AFTER_MS),
        NONE("none", RETRY_NONE_AFTER_MS),
        WITHHELD("withheld", RETRY_NONE_AFTER_MS),
        FAILED("failed", RETRY_FAILED_AFTER_MS),
    }

    private data class Entry(val checkedAt: Long, val outcome: Outcome) {
        // A check time in the future comes from a clock that was wrong then or is wrong now.
        fun isDue(now: Long) = checkedAt > now || now - checkedAt > outcome.retryAfterMs
    }

    private companion object {
        const val TAG = "StationLogos"
        // Raised whenever the lookup changes in a way that can find logos it missed before.
        const val LOOKUP_VERSION_KEY = "lookup.version"
        const val LOOKUP_VERSION = "2"
        // Broadcasters ask for logos to be refreshed at least monthly.
        const val REFRESH_AFTER_MS = 25L * 24 * 60 * 60 * 1000
        const val RETRY_NONE_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        const val RETRY_FAILED_AFTER_MS = 6L * 60 * 60 * 1000
        const val MAX_OFFLINE_FAILURES = 3
        const val MAX_SI_BYTES = 4 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val BITMAP_CACHE_BYTES = 24 * 1024 * 1024
    }
}
