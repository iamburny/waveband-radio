package com.iamburny.waveband.backend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import androidx.core.graphics.createBitmap
import com.iamburny.waveband.Monogram
import com.iamburny.waveband.usb.SupportedTuners
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Demo tuner with no DAB decoding. A scan "finds" three fictional ensembles; playing a service
 * produces a quiet demo tone plus realistic metadata: a rotating dynamic label with song tags,
 * generated slideshow images, a varying signal and, on the national ensemble, a periodic traffic
 * announcement.
 *
 * All state lives on the backend's own executor thread; public methods hand work to it.
 */
class StubDabBackend : DabBackend {

    override val name: String = "Stub"

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "StubDabBackend").apply { isDaemon = true }
    }

    // Executor thread only.
    private var listener: DabBackend.Listener? = null
    private var scanTask: ScheduledFuture<*>? = null
    private var scanFound = mutableListOf<DabServiceInfo>()
    private var playing: DemoService? = null
    private val playbackTasks = mutableListOf<ScheduledFuture<*>>()
    private var announcementEnsemble: Int? = null
    private val announcementTasks = mutableListOf<ScheduledFuture<*>>()
    private var labelIndex = 0
    private var slideIndex = 0
    private var errorRate = 0.01f
    private val random = Random(7)
    private val slides = HashMap<Pair<String, Int>, ByteArray>()

    override fun supports(device: UsbDevice): Boolean = SupportedTuners.isSupported(device)

    override fun open(source: TunerSource, listener: DabBackend.Listener): Boolean {
        executor.execute {
            this.listener = listener
            listener.onTunerStateChanged(TunerState.INITIALISING)
        }
        executor.schedule({ ifCurrent(listener) { onTunerStateChanged(TunerState.READY) } }, 300, TimeUnit.MILLISECONDS)
        return true
    }

    override fun close() {
        executor.submit {
            stopScanInternal(report = false)
            stopPlaybackInternal(report = false)
            listener?.onTunerStateChanged(TunerState.CLOSED)
            listener = null
        }.get()
    }

    override fun startScan(band: DabBand) = onExecutor { l ->
        stopScanInternal(report = false)
        stopPlaybackInternal(report = false)
        l.onTunerStateChanged(TunerState.SCANNING)
        val channels = BandIII.channels
        scanFound = mutableListOf()
        var index = 0
        scanTask = executor.scheduleWithFixedDelay({
            if (listener !== l) return@scheduleWithFixedDelay
            if (index >= channels.size) {
                finishScan(l)
                return@scheduleWithFixedDelay
            }
            val (label, freq) = channels[index]
            l.onScanProgress(ScanProgress(index, channels.size, label))
            SERVICES.filter { it.info.frequencyKHz == freq }.forEach {
                scanFound += it.info
                l.onServiceFound(it.info)
            }
            index++
        }, 0, SCAN_STEP_MS, TimeUnit.MILLISECONDS)
    }

    override fun stopScan() = onExecutor { stopScanInternal(report = true) }

    override fun play(service: DabServiceInfo) = onExecutor { l ->
        stopScanInternal(report = true)
        // A traffic announcement in progress keeps running when switching within its ensemble.
        stopPlaybackInternal(report = false, keepAnnouncements = true)
        val demo = SERVICES.firstOrNull { it.info.key == service.key }
        if (demo == null) {
            l.onTunerStateChanged(TunerState.READY)
            l.onError(BackendError("${service.label} is not on the demo tuner"))
            return@onExecutor
        }
        if (announcementEnsemble != demo.info.ensembleId) stopAnnouncements()
        playing = demo
        labelIndex = 0
        slideIndex = 0
        val format = PcmFormat(48_000, if (demo.stereo) 2 else 1)
        l.onAudioFormatChanged(format)
        l.onTunerStateChanged(TunerState.PLAYING)
        l.onSignal(signalFor(errorRate))
        publishMetadata(l, demo)

        val frameMs = 24L
        val tone = DemoTone(format)
        val frame = ByteArray((format.sampleRate * frameMs / 1000).toInt() * format.channelCount * 2)
        playbackTasks += executor.scheduleWithFixedDelay({
            ifPlaying(l, demo) {
                tone.fill(frame)
                onPcm(frame, frame.size)
            }
        }, 0, frameMs, TimeUnit.MILLISECONDS)
        playbackTasks += executor.scheduleWithFixedDelay({
            ifPlaying(l, demo) {
                labelIndex++
                publishMetadata(this, demo)
            }
        }, LABEL_PERIOD_S, LABEL_PERIOD_S, TimeUnit.SECONDS)
        if (demo.slideshow) {
            playbackTasks += executor.scheduleWithFixedDelay({
                ifPlaying(l, demo) {
                    slideIndex = (slideIndex + 1) % SLIDE_COUNT
                    publishMetadata(this, demo)
                }
            }, SLIDE_PERIOD_S, SLIDE_PERIOD_S, TimeUnit.SECONDS)
        }
        playbackTasks += executor.scheduleWithFixedDelay({
            ifPlaying(l, demo) {
                errorRate = (errorRate + random.nextFloat() * 0.012f - 0.006f).coerceIn(0f, 0.09f)
                onSignal(signalFor(errorRate))
            }
        }, SIGNAL_PERIOD_S, SIGNAL_PERIOD_S, TimeUnit.SECONDS)
        if (demo.info.ensembleId == NATIONAL && announcementEnsemble == null) startAnnouncements(l)
    }

    override fun stopPlayback() = onExecutor { stopPlaybackInternal(report = true) }

    private fun onExecutor(block: (DabBackend.Listener) -> Unit) {
        executor.execute { listener?.let(block) }
    }

    private inline fun ifCurrent(l: DabBackend.Listener, block: DabBackend.Listener.() -> Unit) {
        if (listener === l) l.block()
    }

    private inline fun ifPlaying(l: DabBackend.Listener, demo: DemoService, block: DabBackend.Listener.() -> Unit) {
        if (listener === l && playing === demo) l.block()
    }

    private fun finishScan(l: DabBackend.Listener) {
        scanTask?.cancel(false)
        scanTask = null
        l.onScanFinished(scanFound.toList())
        l.onTunerStateChanged(TunerState.READY)
    }

    private fun stopScanInternal(report: Boolean) {
        val task = scanTask ?: return
        task.cancel(false)
        scanTask = null
        if (report) listener?.let(::finishScan)
    }

    private fun stopPlaybackInternal(report: Boolean, keepAnnouncements: Boolean = false) {
        playbackTasks.forEach { it.cancel(false) }
        playbackTasks.clear()
        val wasPlaying = playing != null
        playing = null
        if (!keepAnnouncements) stopAnnouncements()
        if (report && wasPlaying) listener?.onTunerStateChanged(TunerState.READY)
    }

    private fun publishMetadata(l: DabBackend.Listener, demo: DemoService) {
        val label = demo.labels[labelIndex % demo.labels.size]
        val slide = if (demo.slideshow) slides.getOrPut(demo.info.key to slideIndex) { renderSlide(demo.info, slideIndex) } else null
        // Song labels are tagged the way DL Plus stations tag theirs.
        val item = label.removePrefix(SONG_PREFIX).takeIf { label.startsWith(SONG_PREFIX) }?.split(" – ", limit = 2)
            ?.takeIf { it.size == 2 }
            ?.let { (artist, title) -> PlayingItem(title, artist, running = true, toggle = labelIndex % 2 == 0) }
        l.onMetadata(ServiceMetadata(demo.info.key, label, slide, item))
    }

    /** A traffic bulletin on Travel Desk every few minutes while a national-ensemble service plays. */
    private fun startAnnouncements(l: DabBackend.Listener) {
        announcementEnsemble = NATIONAL
        val travel = SERVICES.first { it.info.serviceId == TRAVEL_DESK }.info
        announcementTasks += executor.scheduleWithFixedDelay({
            if (listener !== l || playing == null) return@scheduleWithFixedDelay
            l.onAnnouncement(Announcement(TRAFFIC_CLUSTER, AnnouncementType.ROAD_TRAFFIC, travel.subchannelId, active = true))
            announcementTasks.removeAll { it.isDone }
            announcementTasks += executor.schedule({
                if (listener === l && playing != null) l.onAnnouncement(Announcement(TRAFFIC_CLUSTER, 0, travel.subchannelId, active = false))
            }, ANNOUNCEMENT_LENGTH_S, TimeUnit.SECONDS)
        }, ANNOUNCEMENT_FIRST_S, ANNOUNCEMENT_PERIOD_S, TimeUnit.SECONDS)
    }

    private fun stopAnnouncements() {
        announcementTasks.forEach { it.cancel(false) }
        announcementTasks.clear()
        announcementEnsemble = null
    }

    /** Reception as the Raontech tuner reports it: its antenna level follows the error rate. */
    private fun signalFor(errorRate: Float): SignalInfo {
        val level = LEVEL_THRESHOLDS.indexOfFirst { errorRate >= it }.let { if (it < 0) 6 else it }
        return SignalInfo(
            locked = true,
            signalLevel = level * 100 / 6,
            snrDb = null,
            errorRate = errorRate,
            audioFrameLoss = ((errorRate - 0.05f) * 10f).coerceIn(0f, 1f),
        )
    }

    /** A chord at roughly broadcast loudness, with a soft pulse and a ticking hi-hat, so the visualisers have something to show. */
    private class DemoTone(private val format: PcmFormat) {
        private var sample = 0L

        fun fill(buffer: ByteArray) {
            val rate = format.sampleRate.toDouble()
            val frames = buffer.size / (2 * format.channelCount)
            for (i in 0 until frames) {
                val t = sample++ / rate
                val chord = CHORDS[((t / 2.0).toInt()) % CHORDS.size]
                var v = chord.sumOf { f -> sin(2 * PI * f * t) } / chord.size * 0.5
                val beat = t % 0.5
                v += sin(2 * PI * 60.0 * t) * exp(-beat * 12.0)
                // The tick alternates between the channels, so the left and right meters differ.
                val tick = sin(2 * PI * 7000.0 * t) * exp(-((t + 0.25) % 0.5) * 40.0) * 0.6
                val tickChannel = ((t + 0.25) / 0.5).toInt() % 2
                for (c in 0 until format.channelCount) {
                    val mixed = if (c == tickChannel || format.channelCount == 1) v + tick else v
                    val s = (mixed * AMPLITUDE * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    val at = (i * format.channelCount + c) * 2
                    buffer[at] = s.toByte()
                    buffer[at + 1] = (s shr 8).toByte()
                }
            }
        }

        private companion object {
            const val AMPLITUDE = 0.3
            val CHORDS = listOf(
                doubleArrayOf(220.0, 277.18, 329.63, 880.0),
                doubleArrayOf(196.0, 246.94, 293.66, 783.99),
                doubleArrayOf(174.61, 220.0, 261.63, 698.46),
                doubleArrayOf(196.0, 246.94, 293.66, 1174.66),
            )
        }
    }

    /** A simple generated slide: gradient in the station colour, its initials and name. */
    private fun renderSlide(service: DabServiceInfo, index: Int): ByteArray {
        val width = 320
        val height = 240
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val base = Monogram.colour(service.serviceId)
        val hsv = FloatArray(3).also { Color.colorToHSV(base, it) }
        val shifted = Color.HSVToColor(floatArrayOf((hsv[0] + if (index == 0) 40f else -60f + 360f) % 360f, hsv[1], hsv[2] * 0.6f))
        val background = Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), base, shifted, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        val rings = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            color = Color.argb(40, 255, 255, 255)
        }
        val cx = if (index == 0) width * 0.85f else width * 0.15f
        for (r in 1..4) canvas.drawCircle(cx, height * 0.2f, r * 34f, rings)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        text.textSize = 84f
        canvas.drawText(Monogram.initials(service.label), width / 2f, 132f, text)
        text.textSize = 24f
        canvas.drawText(service.label, width / 2f, 178f, text)
        text.textSize = 15f
        text.color = Color.argb(200, 255, 255, 255)
        canvas.drawText(if (index == 0) "Demo slideshow" else "Demo slideshow · slide 2", width / 2f, 210f, text)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private class DemoService(
        val info: DabServiceInfo,
        val stereo: Boolean,
        val slideshow: Boolean,
        val labels: List<String>,
    )

    private companion object {
        const val SCAN_STEP_MS = 120L
        const val LABEL_PERIOD_S = 10L
        const val SLIDE_PERIOD_S = 25L
        const val SLIDE_COUNT = 2
        const val SIGNAL_PERIOD_S = 1L
        const val SONG_PREFIX = "Now: "

        /** Error rates at which the antenna level drops to 0, 1, … 5, as on the Raontech tuner. */
        val LEVEL_THRESHOLDS = floatArrayOf(0.081f, 0.07f, 0.049f, 0.04f, 0.025f, 0.018f)
        const val ANNOUNCEMENT_FIRST_S = 60L
        const val ANNOUNCEMENT_PERIOD_S = 180L
        const val ANNOUNCEMENT_LENGTH_S = 15L

        const val NATIONAL = 0xCE11
        const val REGIONAL = 0xCE12
        const val LOCAL = 0xCE13
        const val TRAVEL_DESK = 0xCD05L
        const val TRAFFIC_CLUSTER = 1

        private const val F_11D = 222064
        private const val F_12B = 225648
        private const val F_10C = 213360

        private val TRAFFIC = AnnouncementType.ROAD_TRAFFIC

        private fun service(
            id: Long, ensemble: Int, label: String, pty: Int, kbps: Int, subch: Int,
            stereo: Boolean = true, slideshow: Boolean = false, dabPlus: Boolean = true,
            labels: List<String>,
        ): DemoService {
            val (frequency, ensembleLabel) = when (ensemble) {
                NATIONAL -> F_11D to "Demo National"
                REGIONAL -> F_12B to "Demo Regional"
                else -> F_10C to "Demo Local"
            }
            val national = ensemble == NATIONAL
            return DemoService(
                DabServiceInfo(
                    serviceId = id,
                    ensembleId = ensemble,
                    frequencyKHz = frequency,
                    label = label,
                    shortLabel = label.take(8),
                    ensembleLabel = ensembleLabel,
                    isDabPlus = dabPlus,
                    programmeType = pty,
                    bitrateKbps = kbps,
                    subchannelId = subch,
                    announcementFlags = if (national) TRAFFIC else 0,
                    announcementClusters = if (national) listOf(TRAFFIC_CLUSTER) else emptyList(),
                ),
                stereo, slideshow, labels,
            )
        }

        private fun songs(vararg tracks: String) = tracks.map { "$SONG_PREFIX$it" }

        val SERVICES: List<DemoService> = listOf(
            service(0xCD01, NATIONAL, "Northern Gold", 27, 80, 1, slideshow = true,
                labels = songs("The Lanterns – Paper Boats", "Mira Vale – Northern Lights", "Echo Harbour – Slow Tide")),
            service(0xCD02, NATIONAL, "Harbour Hits", 10, 96, 2, slideshow = true,
                labels = songs("Juno Park – Neon Weekend", "The Daylights – Run to the Sea", "Kit Morrow – Golden Hour")),
            service(0xCD03, NATIONAL, "Coastline Jazz", 24, 64, 3,
                labels = songs("Ada Brennan Trio – Blue Pier", "Sam Okoro – Late Ferry", "The Quayside Five – Lamplight")),
            service(0xCD04, NATIONAL, "Talk Central", 21, 48, 4, stereo = false,
                labels = listOf("Live: the afternoon phone-in – lines are open", "Coming up at four: your questions on the new bypass")),
            service(TRAVEL_DESK, NATIONAL, "Travel Desk", 22, 32, 5, stereo = false,
                labels = listOf("Travel news every 20 minutes", "Road and rail updates for the region")),
            service(0xCD06, NATIONAL, "Metro Sport", 4, 48, 6, stereo = false,
                labels = listOf("Live: second half under way", "Full time: City 2 – 1 Rovers")),
            service(0xCD07, REGIONAL, "Valley Classical", 14, 160, 1, slideshow = true, dabPlus = false,
                labels = songs("Elgar – Enigma Variations", "Holst – The Planets: Jupiter", "Vaughan Williams – The Lark Ascending")),
            service(0xCD08, REGIONAL, "Indie Wave", 11, 96, 2,
                labels = songs("Static Coast – Overpass", "The Ninth Hour – Glasshouse", "Polar Rooms – Sundial")),
            service(0xCD09, REGIONAL, "News Live", 1, 48, 3, stereo = false,
                labels = listOf("Headlines on the hour, every hour", "Business update: markets open higher")),
            service(0xCD0A, REGIONAL, "Little Ears", 18, 48, 4,
                labels = listOf("Story time: The Lighthouse Cat", "Sing-along hour with Pip and Poppy")),
            service(0xCD0B, LOCAL, "Riverside Radio", 9, 128, 1, slideshow = true, dabPlus = false,
                labels = listOf("Riverside Breakfast with Amy and Tom", "Local news, weather and what's on")),
            service(0xCD0C, LOCAL, "Country Roads", 25, 64, 2,
                labels = songs("Hattie Lane – Dust and Diesel", "The Prairie Line – Long Way Home")),
            service(0xCD0D, LOCAL, "Easy Evenings", 12, 64, 3,
                labels = songs("Lena Hart – Moonlit Terrace", "The Velvet Strings – Sunday Drive")),
            service(0xCD0E, LOCAL, "Weather Watch", 16, 32, 4, stereo = false,
                labels = listOf("Dry and bright, highs of 14 degrees", "Showers spreading from the west tonight")),
        )
    }
}
