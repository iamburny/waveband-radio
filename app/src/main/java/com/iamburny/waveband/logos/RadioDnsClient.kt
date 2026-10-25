package com.iamburny.waveband.logos

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** Network side of a RadioDNS logo lookup: DNS queries and HTTP downloads. */
class RadioDnsClient(private val connectivity: ConnectivityManager) {

    /**
     * Thrown when RadioDNS or the broadcaster has nothing usable for a service (no entry, an HTTP
     * error status, an oversized or malformed answer), as opposed to a network failure.
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

    /** Downloads [url], failing if it is larger than [maxBytes], and hands the body to [read]. */
    suspend fun <T> download(url: String, maxBytes: Int, read: (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val parsed = try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw NotFound("Bad URL $url")
        }
        val connection = (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            // Redirects between http and https are not followed and arrive here as 3xx.
            val code = connection.responseCode
            if (code !in 200..299) throw NotFound("$url: HTTP $code")
            if (connection.contentLengthLong > maxBytes) throw NotFound("$url is too large")
            connection.inputStream.use { read(LimitedInputStream(it, maxBytes.toLong())) }
        } finally {
            connection.disconnect()
        }
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
            if (count > limit) throw NotFound("Download exceeds $limit bytes")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
        const val USER_AGENT = "WavebandRadio (RadioDNS logos)"
    }
}
