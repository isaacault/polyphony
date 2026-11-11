package app.polyphony.core.playback.hls

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.OpusUtil
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Spike: can songs streamed as HLS (Opus in fMP4 segments, made by ffmpeg the way continuo would)
 * play gaplessly in Media3?
 *
 * Fixtures: one continuous 440/660 Hz tone at 44.1 kHz, cut sample-exactly into three tracks
 * (441037, 441274 and 440689 samples), each encoded by ffmpeg 9.0.2 libopus into 6 s fMP4 HLS
 * segments. ffmpeg keeps Opus pre-skip (dOps) and shortens the last sample's duration, but writes no
 * gapless metadata, and a decoder always outputs whole 20 ms packets. continuo adds an iTunSMPB entry
 * (delay 0, padding = packets x 960 - declared samples) to each init segment (`*_gapless_init.mp4`).
 *
 * This runs Media3's own FragmentedMp4Extractor (what HlsMediaSource uses for fMP4, with no
 * sideloaded track, so it reads moov/udta) and checks what reaches the renderer: the decoder drops
 * pre-skip from the Opus header, and DefaultAudioSink's TrimmingAudioProcessor drops
 * Format.encoderPadding from the end. What is left must be exactly each track's own length.
 */
class HlsGaplessSpikeTest {

    companion object {
        /**
         * ParsableByteArray asks Build.FINGERPRINT whether it runs under Robolectric; the android.jar
         * stub leaves it null, which Guava's Ascii.equalsIgnoreCase rejects. Give it a value.
         */
        @JvmStatic
        @BeforeClass
        fun stubBuildFingerprint() {
            val field = android.os.Build::class.java.getField("FINGERPRINT")
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
                .apply { isAccessible = true }.get(null) as sun.misc.Unsafe
            @Suppress("DEPRECATION")
            unsafe.putObject(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), "jvm-unit-test")
        }
    }

    private class Extracted(val format: Format, val packetSamples: List<Int>) {
        val decodedSamples get() = packetSamples.sum()
        val preSkipSamples: Int
            get() {
                val nanos = ByteBuffer.wrap(format.initializationData[1]).order(ByteOrder.nativeOrder()).long
                return (nanos * 48_000 / 1_000_000_000).toInt()
            }
    }

    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("hls-gapless/$name")) { name }.readBytes()

    private fun extract(init: String, segments: List<String>): Extracted {
        val data = (listOf(init) + segments).map(::bytes).reduce(ByteArray::plus)
        var position = 0
        val reader = DataReader { buffer, offset, length ->
            if (position >= data.size) {
                C.RESULT_END_OF_INPUT
            } else {
                val n = minOf(length, data.size - position)
                System.arraycopy(data, position, buffer, offset, n)
                position += n
                n
            }
        }
        var format: Format? = null
        val sampleBytes = ByteArrayOutputStream()
        val packets = mutableListOf<Int>()
        val track = object : TrackOutput {
            override fun format(f: Format) {
                if (f.sampleMimeType == MimeTypes.AUDIO_OPUS) format = f
            }

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val buffer = ByteArray(length)
                val read = input.read(buffer, 0, length)
                if (read == C.RESULT_END_OF_INPUT) return read
                sampleBytes.write(buffer, 0, read)
                return read
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                val buffer = ByteArray(length)
                data.readBytes(buffer, 0, length)
                sampleBytes.write(buffer)
            }

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                val all = sampleBytes.toByteArray()
                val end = all.size - offset
                val packet = all.copyOfRange(end - size, end)
                packets += (OpusUtil.getPacketDurationUs(packet) * 48_000 / 1_000_000).toInt()
            }
        }
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = track
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        }
        val extractor = FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        extractor.init(output)
        val input = DefaultExtractorInput(reader, 0, data.size.toLong())
        val holder = PositionHolder()
        while (true) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_END_OF_INPUT) break
            check(result == Extractor.RESULT_CONTINUE) { "unexpected seek to ${holder.position}" }
        }
        return Extracted(checkNotNull(format) { "no Opus track" }, packets)
    }

    private fun segments(name: String) = listOf("${name}_0.m4s", "${name}_1.m4s")

    /** Samples left after the decoder's pre-skip and the sink's end trim. */
    private fun played(e: Extracted) = e.decodedSamples - e.preSkipSamples - e.format.encoderDelay - e.format.encoderPadding

    private val sourceSamples44k = mapOf("t1" to 441_037, "t2" to 441_274, "t3" to 440_689)

    private fun expected48k(track: String) = sourceSamples44k.getValue(track) * 48_000.0 / 44_100

    @Test
    fun `each track plays exactly its own length when continuo states the padding`() {
        for (track in listOf("t1", "t2", "t3")) {
            val e = extract("${track}_opus64_gapless_init.mp4", segments("${track}_opus64"))
            println("SPIKE $track: packets ${e.packetSamples.size} decoded ${e.decodedSamples} preSkip ${e.preSkipSamples} " +
                "delay ${e.format.encoderDelay} padding ${e.format.encoderPadding} played ${played(e)} " +
                "expected ${"%.2f".format(expected48k(track))}")
            assertEquals("$track dOps pre-skip is zeroed, so the decoder skips nothing", 0, e.preSkipSamples)
            assertEquals("$track delay comes from iTunSMPB", 312, e.format.encoderDelay)
            assertTrue("$track padding is stated", e.format.encoderPadding > 0)
            assertTrue("$track plays its own length", abs(played(e) - expected48k(track)) < 1.0)
        }
    }

    @Test
    fun `the three tracks join into the original tone with nothing added`() {
        val total = listOf("t1", "t2", "t3").sumOf { played(extract("${it}_opus64_gapless_init.mp4", segments("${it}_opus64"))) }
        val whole = 1_323_000 * 48_000.0 / 44_100
        println("SPIKE joined: $total samples vs ${"%.2f".format(whole)} for the uncut tone")
        assertTrue("joined length within 1 sample per join", abs(total - whole) <= 3.0)
    }

    @Test
    fun `ffmpeg's own init loses the start of every song in Media3 and keeps the tail`() {
        // dOps is big-endian (ISO BMFF Opus); Media3 1.11.1 copies it into a little-endian
        // OpusHead, so PreSkip 312 (0x0138) is read as 14337 (0x3801): ~0.3 s cut from each start.
        val e = extract("t1_opus64_plain_init.mp4", segments("t1_opus64"))
        val diff = played(e) - expected48k("t1")
        println("SPIKE plain t1: preSkip ${e.preSkipSamples} padding ${e.format.encoderPadding}, " +
            "played ${"%.1f".format(diff)} samples (${"%.1f".format(diff / 48)} ms) off")
        assertEquals(0x3801, e.preSkipSamples)
        assertEquals(0, e.format.encoderPadding)
    }

    @Test
    fun `switching quality mid-song keeps the same trims`() {
        val low = extract("t1_opus64_gapless_init.mp4", segments("t1_opus64"))
        val high = extract("t1_opus128_gapless_init.mp4", segments("t1_opus128"))
        assertEquals(low.packetSamples, high.packetSamples)
        assertEquals(low.preSkipSamples, high.preSkipSamples)
        assertEquals(low.format.encoderPadding, high.format.encoderPadding)
    }
}
