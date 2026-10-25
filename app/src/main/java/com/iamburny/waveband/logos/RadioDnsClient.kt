package com.iamburny.waveband.logos

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** Network side of a RadioDNS logo lookup: DNS queries and HTTP downloads. */
class RadioDnsClient(private val connectivity: ConnectivityManager) {

    /**
     * Thrown when RadioDNS or the broadcaster says there is nothing for a service: no RadioDNS
     * entry, no SPI server, or HTTP 404/410. Other failures are [IOException]s and may pass.
     */
    class NotFound(message: String) : Exception(message)

    /** The broadcaster's domain for the service named [lookupName]: the CNAME of the RadioDNS name. */
    suspend fun broadcasterDomain(lookupName: String): String =
        DnsMessage.cnames(query(lookupName, DnsMessage.TYPE_CNAME)).firstOrNull()?.trimEnd('.')
            ?: throw NotFound("No RadioDNS entry for $lookupName")

    /** URL of the broadcaster's SI file, from the `_radiospi._tcp` SRV record of its [domain]. */
    suspend fun serviceInformationUrl(domain: String): String {
        val srv = DnsMessage.srvs(query("_radiospi._tcp.$domain", DnsMessage.TYPE_SRV)).firstOrNull()
            ?: throw NotFound("No SPI server for $domain")
        val host = srv.target.trimEnd('.')
        // A target of "." means the service is deliberately not offered.
        if (host.isEmpty()) throw NotFound("No SPI server for $domain")
        return when (srv.port) {
            443 -> "https://$host/radiodns/spi/3.1/SI.xml"
            80 -> "http://$host/radiodns/spi/3.1/SI.xml"
            else -> "http://$host:${srv.port}/radiodns/spi/3.1/SI.xml"
        }
    }

    /**
     * Downloads [url], following up to [MAX_REDIRECTS] redirects (including between http and
     * https), fails if the body is larger than [maxBytes] or the whole download, redirects
     * included, takes longer than [DOWNLOAD_DEADLINE_MS], and hands the body to [read].
     */
    suspend fun <T> download(url: String, maxBytes: Int, read: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val active = AtomicReference<HttpURLConnection?>(null)
        val expired = AtomicBoolean(false)
        // Closing the connection is the only way to stop blocking reads: on cancellation, or when
        // the deadline passes.
        val watchdog = launch {
            try {
                delay(DOWNLOAD_DEADLINE_MS)
            } finally {
                expired.set(true)
                active.get()?.disconnect()
            }
        }
        try {
            fetch(URL(url), maxBytes, read, active, expired)
        } finally {
            watchdog.cancel()
        }
    }

    private fun <T> fetch(
        start: URL,
        maxBytes: Int,
        read: (InputStream) -> T,
        active: AtomicReference<HttpURLConnection?>,
        expired: AtomicBoolean,
    ): T {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
            active.set(connection)
            try {
                if (expired.get()) throw IOException("Download of $start took too long")
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("$current: HTTP $code without a location")
                        current = URL(current, location)
                        if (current.protocol != "http" && current.protocol != "https") throw IOException("Redirect to $current")
                        return@repeat
                    }
                    code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE -> throw NotFound("$current: HTTP $code")
                    code !in 200..299 -> throw IOException("$current: HTTP $code")
                }
                if (connection.contentLengthLong > maxBytes) throw IOException("$current is too large")
                return connection.inputStream.use { read(LimitedInputStream(it, maxBytes.toLong())) }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects from $start")
    }

    private suspend fun query(name: String, type: Int): ByteArray {
        val network = connectivity.activeNetwork ?: throw IOException("No network")
        // A timeout is a network failure, not a cancellation of the caller.
        val answer = withTimeoutOrNull(TIMEOUT_MS.toLong()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rawQuery(network, name, type) else udpQuery(network, name, type)
        } ?: throw IOException("DNS timeout for $name")
        return when (DnsMessage.responseCode(answer)) {
            0 -> answer
            3 -> throw NotFound("$name does not exist")
            else -> throw IOException("DNS error ${DnsMessage.responseCode(answer)} for $name")
        }
    }

    /** Queries through the system resolver, which also applies Private DNS. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun rawQuery(network: Network, name: String, type: Int): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            DnsResolver.getInstance().rawQuery(
                network, name, DnsResolver.CLASS_IN, type, DnsResolver.FLAG_EMPTY, DirectExecutor, cancel,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) = continuation.resume(answer)
                    override fun onError(error: DnsResolver.DnsException) = continuation.resumeWithException(IOException(error))
                },
            )
        }

    /** Before Android 10 there is no raw query API, so this asks the network's DNS server directly. */
    private suspend fun udpQuery(network: Network, name: String, type: Int): ByteArray = withContext(Dispatchers.IO) {
        val server = connectivity.getLinkProperties(network)?.dnsServers?.firstOrNull() ?: throw IOException("No DNS server")
        val id = Random.nextInt(0x10000)
        val request = DnsMessage.query(id, name, type)
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            socket.soTimeout = TIMEOUT_MS
            socket.send(DatagramPacket(request, request.size, server, 53))
            val buffer = ByteArray(4096)
            // Skips stray datagrams that do not answer this query.
            repeat(8) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val answer = buffer.copyOf(packet.length)
                if (packet.length >= 12 && DnsMessage.id(answer) == id) return@withContext answer
            }
            throw IOException("No DNS answer for $name")
        }
    }

    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    /** Fails once more than [limit] bytes have been read. */
    private class LimitedInputStream(private val source: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            val b = source.read()
            if (b >= 0) check(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = source.read(b, off, len)
            if (n > 0) check(n)
            return n
        }

        private fun check(n: Int) {
            count += n
            if (count > limit) throw IOException("Download exceeds $limit bytes")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val DOWNLOAD_DEADLINE_MS = 30_000L
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "WavebandRadio (RadioDNS logos)"
    }
}
