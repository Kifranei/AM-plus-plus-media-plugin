package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MotionArtworkRemuxTest {
    @get:Rule val temporary = TemporaryFolder()
    private val options = ContentExportOptions()
    private fun control(options: ContentExportOptions = this.options) = ContentExportControl(options).apply { start() }

    @Test fun `trex defaults flatten two fragments and retain codec and color bytes`() {
        val init = initialization()
        val first = listOf(Frame(1), Frame(2, flags = NON_SYNC))
        val second = listOf(Frame(3), Frame(4, flags = NON_SYNC))
        val file = source(init + fragment(first, 9000, runFlags = 1) + fragment(second, 9080, runFlags = 1))
        // Non-key defaults are tested separately; these trex flags declare every sample sync.
        val originalDescription = find(init, "stsd").raw
        MotionArtworkRemux.normalize(file, options, control())
        val output = file.readBytes()
        assertFalse(top(output).any { it.type == "moof" })
        assertFalse(all(output).any { it.type in setOf("mvex", "edts") })
        assertArrayEquals(originalDescription, find(output, "stsd").raw)
        assertEquals(listOf(4L to 40L), table(output, "stts"))
        assertEquals(listOf(1L, 2L, 3L, 4L), words(find(output, "stss").payload, 8))
        assertSamples(output, first + second)
        assertDurations(output, movie = 160, media = 160)
        assertEquals(1L, MotionArtworkMp4.validate(file, control()).id)
        assertNoRemuxFiles()
    }

    @Test fun `tfhd defaults override trex and absent offsets continue previous trun`() {
        val frames = listOf(Frame(1, duration = 50), Frame(2, duration = 50, flags = NON_SYNC),
            Frame(3, duration = 50, flags = NON_SYNC))
        val init = initialization(defaultDuration = 17, defaultSize = 8, defaultFlags = NON_SYNC)
        val file = source(init + fragment(frames, 700, runFlags = 5, split = 1,
            tfhdDefaults = Triple(50, 4, NON_SYNC), firstFlags = 0))
        MotionArtworkRemux.normalize(file, options, control())
        val output = file.readBytes()
        assertEquals(listOf(3L to 50L), table(output, "stts"))
        assertEquals(listOf(1L), words(find(output, "stss").payload, 8))
        assertEquals(listOf(1L, 1L, 1L), words(find(output, "stsc").payload, 8))
        assertSamples(output, frames)
        assertDurations(output, 150, 150)
    }

    @Test fun `explicit base data offset and signed trun offset are accepted`() {
        val init = initialization()
        val frames = listOf(Frame(1), Frame(2, flags = NON_SYNC))
        val relative = fragment(frames, 500, explicitBase = init.size.toLong(), offsetAdjustment = -20)
        // base is moof start + 20; the negative adjustment restores the real data address.
        val file = source(init + relative)
        MotionArtworkRemux.normalize(file, options, control())
        assertSamples(file.readBytes(), frames)
        assertDurations(file.readBytes(), 80, 80)
    }

    @Test fun `tfhd data base without trun offset directly addresses mdat`() {
        val init = initialization()
        val frames = listOf(Frame(1), Frame(2))
        val file = source(init + fragment(frames, 0, runFlags = 0x300, directBase = init.size.toLong()))
        MotionArtworkRemux.normalize(file, options, control())
        assertSamples(file.readBytes(), frames)
    }

    @Test fun `unsigned B frame composition offsets normalize to signed ctts`() {
        val frames = listOf(Frame(1, cts = 80), Frame(2, flags = NON_SYNC, cts = 160),
            Frame(3, flags = NON_SYNC, cts = 40), Frame(4, flags = NON_SYNC, cts = 40),
            Frame(5, flags = NON_SYNC, cts = 120), Frame(6, flags = NON_SYNC, cts = 40))
        val file = source(initialization(withEdits = true) + fragment(frames, 0x100000100L))
        MotionArtworkRemux.normalize(file, options, control())
        val output = file.readBytes()
        assertEquals(1, find(output, "ctts").payload[0].toInt())
        assertEquals(listOf(1L to 0L, 1L to 80L, 2L to -40L, 1L to 40L, 1L to -40L), table(output, "ctts", signed = true))
        assertEquals(listOf(0L, 120L, 40L, 80L, 200L, 160L), presentation(output))
        assertFalse(all(output).any { it.type == "edts" })
        assertSamples(output, frames)
        assertDurations(output, 240, 240)
    }

    @Test fun `version one signed offsets and negative presentation origin keep B frame order`() {
        val frames = listOf(Frame(1, cts = -40), Frame(2, flags = NON_SYNC, cts = 40),
            Frame(3, flags = NON_SYNC, cts = -80), Frame(4, flags = NON_SYNC, cts = -80))
        val file = source(initialization() + fragment(frames, 100, signed = true))
        MotionArtworkRemux.normalize(file, options, control())
        val output = file.readBytes()
        assertEquals(listOf(1L to 0L, 1L to 80L, 2L to -40L), table(output, "ctts", signed = true))
        assertEquals(listOf(0L, 120L, 40L, 80L), presentation(output))
        assertDurations(output, 160, 160)
        assertSamples(output, frames)
    }

    @Test fun `variable sample sizes durations sync flags and multiple descriptions survive`() {
        val first = listOf(Frame(1, duration = 30, bytes = byteArrayOf(1, 2)),
            Frame(2, duration = 50, flags = NON_SYNC, bytes = byteArrayOf(3, 4, 5)))
        val second = listOf(Frame(3, duration = 60, bytes = byteArrayOf(6, 7, 8, 9, 10)))
        val file = source(initialization(descriptions = 2) + fragment(first, 40) + fragment(second, 120, description = 2))
        MotionArtworkRemux.normalize(file, options, control())
        val output = file.readBytes()
        assertEquals(listOf(1L to 30L, 1L to 50L, 1L to 60L), table(output, "stts"))
        assertEquals(listOf(1L, 3L), words(find(output, "stss").payload, 8))
        assertEquals(listOf(1L, 1L, 1L, 3L, 1L, 2L), words(find(output, "stsc").payload, 8))
        assertSamples(output, first + second)
        assertDurations(output, 140, 140)
    }

    @Test fun `large media duration promotes version zero headers without damaging fields`() {
        val frames = listOf(Frame(1, duration = Int.MAX_VALUE), Frame(2, duration = Int.MAX_VALUE),
            Frame(3, duration = Int.MAX_VALUE))
        val file = source(initialization(mediaScale = 100_000_000) + fragment(frames, 0))
        MotionArtworkRemux.normalize(file, options, control())
        val output = file.readBytes()
        val header = find(output, "mdhd").payload
        assertEquals(1, header[0].toInt())
        assertEquals(100_000_000L, u32(header, 20))
        assertEquals(3L * Int.MAX_VALUE, ByteBuffer.wrap(header).getLong(24))
        assertEquals(0x55c4, ByteBuffer.wrap(header).getShort(32).toInt())
        assertDurations(output, 64425, 3L * Int.MAX_VALUE)
    }

    @Test fun `version one movie track and media headers retain flags and dimensions`() {
        val frames = listOf(Frame(1), Frame(2))
        val file = source(initialization(versionOne = true) + fragment(frames, 123))
        MotionArtworkRemux.normalize(file, options, control())
        val output = file.readBytes()
        assertEquals(1, find(output, "mvhd").payload[0].toInt())
        assertEquals(0x01000007L, u32(find(output, "tkhd").payload, 0))
        assertEquals(320, MotionArtworkMp4.validate(file, control()).width)
        assertDurations(output, 80, 80)
    }

    @Test fun `ordinary output is verified and retained byte for byte on a second call`() {
        val file = source(initialization() + fragment(listOf(Frame(1), Frame(2)), 0))
        MotionArtworkRemux.normalize(file, options, control())
        val normalized = file.readBytes()
        MotionArtworkRemux.normalize(file, options, control())
        assertArrayEquals(normalized, file.readBytes())
        assertNoRemuxFiles()
    }

    @Test fun `invalid samples timing addressing and counts leave source intact`() {
        val init = initialization()
        val pair = listOf(Frame(1), Frame(2, flags = NON_SYNC))
        val cases = listOf(
            init + fragment(listOf(Frame(1)), 0),
            init + fragment(listOf(Frame(1, duration = 0), Frame(2)), 0),
            init + fragment(listOf(Frame(1, bytes = byteArrayOf()), Frame(2)), 0),
            init + fragment(listOf(Frame(1), Frame(2, cts = -40)), 0, signed = true), // same PTS
            init + fragment(pair, 100) + fragment(pair, 190), // gap
            init + fragment(pair, 100) + fragment(pair, 140), // overlap
            init + fragment(pair, 0, offsetAdjustment = -500),
            init + fragment(pair, 0, offsetAdjustment = 5),
            init + fragment(pair, 0, countOverride = 3),
            init + fragment(pair, 0, tfhdTrack = 2),
            init + fragment(pair, 0, description = 2),
            init + fragment(pair, 0, runFlags = 0x705), // first_flags and sample_flags conflict
            init + fragment(pair, 0, truncated = true),
            init + fragment(pair.map { it.copy(flags = NON_SYNC) }, 0),
        )
        cases.forEachIndexed { index, bytes ->
            val file = source(bytes, "bad-$index.mp4")
            assertReason(ContentExportReason.INVALID_MEDIA) { MotionArtworkRemux.normalize(file, options, control()) }
            assertArrayEquals(bytes, file.readBytes())
            assertNoRemuxFiles()
        }
    }

    @Test fun `encryption and audio are rejected without replacing source`() {
        for ((init, reason) in listOf(initialization(encrypted = true) to ContentExportReason.ENCRYPTED_MEDIA,
            initialization(handler = "soun") to ContentExportReason.AUDIO_NOT_ALLOWED)) {
            val bytes = init + fragment(listOf(Frame(1), Frame(2)), 0)
            val file = source(bytes)
            assertReason(reason) { MotionArtworkRemux.normalize(file, options, control()) }
            assertArrayEquals(bytes, file.readBytes())
            assertNoRemuxFiles()
            assertTrue(file.delete())
        }
    }

    @Test fun `sample count bytes and decode or presentation durations are bounded`() {
        val bytes = initialization() + fragment(listOf(Frame(1), Frame(2)), 0)
        val file = source(bytes)
        val small = options.copy(maxBytes = bytes.size.toLong() - 1)
        assertReason(ContentExportReason.SIZE_LIMIT) { MotionArtworkRemux.normalize(file, small, control(small)) }
        val short = options.copy(maxHlsDurationSeconds = 0.05)
        assertReason(ContentExportReason.PLAYLIST_LIMIT) { MotionArtworkRemux.normalize(file, short, control(short)) }
        assertArrayEquals(bytes, file.readBytes())
        val many = source(initialization() + fragment(listOf(Frame(1), Frame(2)), 0, countOverride = 200_001), "many.mp4")
        assertReason(ContentExportReason.PLAYLIST_LIMIT) { MotionArtworkRemux.normalize(many, options, control()) }
        val presentation = source(initialization() + fragment(listOf(Frame(1), Frame(2, cts = 400_000)), 0), "long-pts.mp4")
        assertReason(ContentExportReason.PLAYLIST_LIMIT) { MotionArtworkRemux.normalize(presentation, options, control()) }
        assertNoRemuxFiles()
    }

    @Test fun `output metadata exceeding maxBytes is rejected before creating temporary file`() {
        // One duration/size/flag/CTS per sample in output, but all defaults in input.
        val frames = (1..200).map { Frame(it) }
        val bytes = initialization() + fragment(frames, 0, runFlags = 1)
        val file = source(bytes)
        val limited = options.copy(maxBytes = bytes.size.toLong())
        assertReason(ContentExportReason.SIZE_LIMIT) { MotionArtworkRemux.normalize(file, limited, control(limited)) }
        assertArrayEquals(bytes, file.readBytes())
        assertNoRemuxFiles()
    }

    @Test fun `pre cancellation timeout and thread interruption leave source intact`() {
        val bytes = initialization() + fragment(listOf(Frame(1), Frame(2)), 0)
        val file = source(bytes)
        val cancelled = control().apply { cancel() }
        assertReason(ContentExportReason.CANCELLED) { MotionArtworkRemux.normalize(file, options, cancelled) }
        val quick = options.copy(maxExportDurationMillis = 1)
        val expired = control(quick)
        Thread.sleep(5)
        assertReason(ContentExportReason.TIMEOUT) { MotionArtworkRemux.normalize(file, quick, expired) }
        Thread.currentThread().interrupt()
        try { assertReason(ContentExportReason.CANCELLED) { MotionArtworkRemux.normalize(file, options, control()) } }
        finally { Thread.interrupted() }
        assertArrayEquals(bytes, file.readBytes())
        assertNoRemuxFiles()
    }

    @Test fun `cancellation during disk copy removes temporary output and preserves source`() {
        val init = initialization()
        val size = 128 * 1024 * 1024
        val frames = listOf(Frame(1), Frame(2, sizeOverride = size))
        // Sparse payload gives a long, bounded copy without allocating a giant fixture in memory.
        val prefix = init + fragment(frames, 0, sparse = true)
        val file = source(prefix)
        RandomAccessFile(file, "rw").use { it.setLength(prefix.size.toLong() + size) }
        val originalLength = file.length()
        val cancelled = control()
        val error = AtomicReference<Throwable?>()
        val worker = Thread {
            try { MotionArtworkRemux.normalize(file, options, cancelled) }
            catch (failure: Throwable) { error.set(failure) }
        }
        worker.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (worker.isAlive && System.nanoTime() < deadline && remuxFiles().isEmpty()) Thread.sleep(1)
        val observedTemporary = remuxFiles().isNotEmpty()
        cancelled.cancel()
        worker.join(10_000)
        assertFalse("copy did not terminate", worker.isAlive)
        assertTrue("test must cancel after temporary creation", observedTemporary)
        assertEquals(ContentExportReason.CANCELLED, (error.get() as ContentExportException).failure.reason)
        assertEquals(originalLength, file.length())
        RandomAccessFile(file, "r").use { input -> assertArrayEquals(prefix, ByteArray(prefix.size).also(input::readFully)) }
        assertNoRemuxFiles()
    }

    /** Opt-in local JUnit harness; never requires a device or distributes private CDN fixtures. */
    @Test fun `optional real HEVC files normalize through the same production interface`() {
        val fixtures = System.getenv("AMPP_REMUX_FIXTURES")
        assumeTrue("Set AMPP_REMUX_FIXTURES to semicolon-separated input files", !fixtures.isNullOrBlank())
        val outputDirectory = System.getenv("AMPP_REMUX_OUTPUT")?.let(::File) ?: temporary.newFolder("real")
        assertTrue(outputDirectory.isDirectory || outputDirectory.mkdirs())
        fixtures!!.split(';').forEach { path ->
            val source = File(path)
            assertTrue(source.isFile)
            val output = File(outputDirectory, source.nameWithoutExtension + "-normalized.mp4")
            assertNotEquals(source.canonicalPath, output.canonicalPath)
            source.copyTo(output, overwrite = true)
            MotionArtworkRemux.normalize(output, options, control())
            val boxes = all(output.readBytes())
            assertFalse(boxes.any { it.type in setOf("moof", "mvex", "edts") })
            assertTrue(u32(boxes.single { it.type == "stsz" }.payload, 8) > 1)
            MotionArtworkMp4.validate(output, control())
            println("REMUX_VERIFIED ${output.absolutePath} ${output.length()}")
        }
    }

    private fun source(bytes: ByteArray, name: String = "source.mp4") = File(temporary.root, name).apply { writeBytes(bytes) }
    private fun remuxFiles() = temporary.root.listFiles()!!.filter { it.name.startsWith(".motion-remux-") }
    private fun assertNoRemuxFiles() = assertTrue(remuxFiles().isEmpty())
    private fun assertReason(reason: ContentExportReason, block: () -> Unit) {
        assertEquals(reason, assertThrows(ContentExportException::class.java) { block() }.failure.reason)
    }
    private fun assertSamples(bytes: ByteArray, frames: List<Frame>) {
        val sizes = words(find(bytes, "stsz").payload, 12)
        val offsets = words(find(bytes, "stco").payload, 8)
        assertEquals(frames.size, u32(find(bytes, "stsz").payload, 8).toInt())
        assertEquals(frames.size, offsets.size)
        val mdat = top(bytes).single { it.type == "mdat" }
        frames.forEachIndexed { i, frame ->
            assertEquals(frame.bytes.size.toLong(), sizes[i])
            assertTrue(offsets[i] >= mdat.start + 8)
            assertTrue(offsets[i] + sizes[i] <= mdat.end)
            assertArrayEquals(frame.bytes, bytes.copyOfRange(offsets[i].toInt(), (offsets[i] + sizes[i]).toInt()))
        }
        assertArrayEquals(frames.fold(byteArrayOf()) { data, frame -> data + frame.bytes }, mdat.payload)
    }
    private fun assertDurations(bytes: ByteArray, movie: Long, media: Long) {
        for ((type, expected) in listOf("mvhd" to movie, "tkhd" to movie, "mdhd" to media)) {
            val header = find(bytes, type).payload
            val offset = if (type == "tkhd") 20 else 16
            val actual = if (header[0] == 0.toByte()) u32(header, offset) else ByteBuffer.wrap(header).getLong(offset + 8)
            assertEquals(type, expected, actual)
        }
    }
    private fun table(bytes: ByteArray, type: String, signed: Boolean = false): List<Pair<Long, Long>> {
        val data = find(bytes, type).payload
        return (0 until u32(data, 4).toInt()).map { i ->
            u32(data, 8 + i * 8) to if (signed) ByteBuffer.wrap(data).getInt(12 + i * 8).toLong() else u32(data, 12 + i * 8)
        }
    }
    private fun presentation(bytes: ByteArray): List<Long> {
        val durations = table(bytes, "stts").flatMap { (n, value) -> List(n.toInt()) { value } }
        val offsets = table(bytes, "ctts", find(bytes, "ctts").payload[0] == 1.toByte()).flatMap { (n, value) -> List(n.toInt()) { value } }
        var dts = 0L
        return durations.indices.map { i -> (dts + offsets[i]).also { dts += durations[i] } }
    }
    private data class TestBox(val type: String, val start: Int, val end: Int, val bytes: ByteArray) {
        val raw: ByteArray get() = bytes.copyOfRange(start, end)
        val payload: ByteArray get() = bytes.copyOfRange(start + 8, end)
    }
    private fun top(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): List<TestBox> {
        val result = ArrayList<TestBox>()
        var offset = start
        while (offset < end) {
            val size = u32(bytes, offset).toInt()
            assertTrue(size >= 8 && size <= end - offset)
            result += TestBox(String(bytes, offset + 4, 4, StandardCharsets.US_ASCII), offset, offset + size, bytes)
            offset += size
        }
        assertEquals(end, offset)
        return result
    }
    private fun all(bytes: ByteArray): List<TestBox> {
        fun recurse(box: TestBox): List<TestBox> = listOf(box) + if (box.type in setOf("moov", "trak", "mdia", "minf", "stbl", "mvex", "edts")) {
            top(bytes, box.start + 8, box.end).flatMap(::recurse)
        } else emptyList()
        return top(bytes).flatMap(::recurse)
    }
    private fun find(bytes: ByteArray, type: String) = all(bytes).single { it.type == type }
    private fun words(bytes: ByteArray, start: Int) = (start until bytes.size step 4).map { u32(bytes, it) }
    private fun u32(bytes: ByteArray, offset: Int) = ByteBuffer.wrap(bytes).getInt(offset).toLong() and 0xffffffffL

    private data class Frame(val id: Int, val duration: Int = 40, val flags: Int = 0, val cts: Int = 0,
                             val bytes: ByteArray = ints(id), val sizeOverride: Int? = null)

    private fun initialization(defaultDuration: Int = 40, defaultSize: Int = 4, defaultFlags: Int = 0,
                               withEdits: Boolean = false, mediaScale: Int = 1000, versionOne: Boolean = false,
                               descriptions: Int = 1, encrypted: Boolean = false, handler: String = "vide"): ByteArray {
        val tkhd = ByteArray(84).also { ByteBuffer.wrap(it).apply {
            putInt(0, 7); putInt(12, 1); putInt(20, 999); putInt(40, 0x10000); putInt(56, 0x10000)
            putInt(72, 0x40000000); putInt(76, 320 shl 16); putInt(80, 240 shl 16)
        } }
        val mvhd = ByteArray(100).also { ByteBuffer.wrap(it).apply {
            putInt(12, 1000); putInt(16, 999); putInt(20, 0x10000); putShort(24, 0x100)
            putInt(36, 0x10000); putInt(52, 0x10000); putInt(68, 0x40000000); putInt(96, 2)
        } }
        val mdhd = ByteArray(24).also { ByteBuffer.wrap(it).apply { putInt(12, mediaScale); putInt(16, 999); putShort(20, 0x55c4) } }
        fun promote(bytes: ByteArray, track: Boolean = false): ByteArray {
            if (!versionOne) return bytes
            val durationOffset = if (track) 20 else 16
            return data {
                writeInt(ByteBuffer.wrap(bytes).getInt(0) or 0x01000000)
                writeLong(123); writeLong(456); write(bytes, 12, durationOffset - 12)
                writeLong(999); write(bytes, durationOffset + 4, bytes.size - durationOffset - 4)
            }
        }
        val entry = ByteArray(78).also { ByteBuffer.wrap(it).apply {
            putShort(6, 1); putShort(24, 320); putShort(26, 240); putInt(28, 0x480000); putInt(32, 0x480000)
            putShort(40, 1); putShort(74, 24); putShort(76, -1)
        } }
        val codec = box(if (encrypted) "encv" else "avc1", entry,
            box("avcC", byteArrayOf(1, 0x42, 0, 0x1e, -1, -32, 0)), box("colr", ascii("nclx"), byteArrayOf(0, 1, 0, 1, 0, 1, -128)))
        val stsd = box("stsd", ints(0, descriptions), *Array(descriptions) { codec })
        val stbl = box("stbl", stsd, box("stts", ints(0, 0)), box("stsc", ints(0, 0)), box("stsz", ints(0, 0, 0)), box("stco", ints(0, 0)))
        val media = box("mdia", box("mdhd", promote(mdhd)), box("hdlr", ints(0, 0), ascii(handler), ByteArray(12), ascii("Artwork\u0000")),
            box("minf", box("vmhd", ints(1), ByteArray(8)), box("dinf", box("dref", ints(0, 1), box("url ", ints(1)))), stbl))
        val edits = if (withEdits) box("edts", box("elst", ints(0, 1, 999, 9000, 0x10000))) else byteArrayOf()
        return box("ftyp", ascii("iso6"), ints(1), ascii("iso6mp41")) + box("moov", box("mvhd", promote(mvhd)),
            box("trak", box("tkhd", promote(tkhd, true)), edits, media), box("mvex", box("trex", ints(0, 1, 1, defaultDuration, defaultSize, defaultFlags))))
    }

    private fun fragment(frames: List<Frame>, time: Long, runFlags: Int = 0xf01, signed: Boolean = false,
                         tfhdDefaults: Triple<Int, Int, Int>? = null, firstFlags: Int = 0, split: Int? = null,
                         explicitBase: Long? = null, directBase: Long? = null, offsetAdjustment: Int = 0,
                         countOverride: Int? = null, tfhdTrack: Int = 1, description: Int? = null,
                         truncated: Boolean = false, sparse: Boolean = false): ByteArray {
        val groups = if (split == null) listOf(frames) else listOf(frames.take(split), frames.drop(split))
        fun moof(dataOffset: Int): ByteArray {
            val headerFlags = (if (explicitBase != null || directBase != null) 1 else 0x020000) or
                (if (tfhdDefaults != null) 0x38 else 0) or (if (description != null) 2 else 0)
            val tfhd = box("tfhd", data {
                writeInt(headerFlags); writeInt(tfhdTrack)
                if (explicitBase != null) writeLong(explicitBase - offsetAdjustment)
                if (directBase != null) writeLong(directBase + dataOffset)
                description?.let { writeInt(it) }
                tfhdDefaults?.let { writeInt(it.first); writeInt(it.second); writeInt(it.third) }
            })
            val tfdt = box("tfdt", if (time <= 0xffffffffL) ints(0, time.toInt()) else data { writeInt(0x01000000); writeLong(time) })
            val runs = groups.mapIndexed { i, group -> box("trun", data {
                val flags = if (i == 0) runFlags else runFlags and 5.inv()
                writeInt(flags or if (signed) 0x01000000 else 0); writeInt(countOverride ?: group.size)
                if (flags and 1 != 0) writeInt(dataOffset + offsetAdjustment)
                if (flags and 4 != 0) writeInt(firstFlags)
                group.forEach { frame ->
                    if (flags and 0x100 != 0) writeInt(frame.duration)
                    if (flags and 0x200 != 0) writeInt(frame.sizeOverride ?: frame.bytes.size)
                    if (flags and 0x400 != 0) writeInt(frame.flags)
                    if (flags and 0x800 != 0) writeInt(frame.cts)
                }
            }) }
            return box("moof", box("mfhd", ints(0, 1)), box("traf", tfhd, tfdt, *runs.toTypedArray()))
        }
        val provisional = moof(0)
        val movie = moof(provisional.size + 8)
        val payload = frames.fold(byteArrayOf()) { bytes, frame -> bytes + if (sparse && frame.sizeOverride != null) byteArrayOf() else frame.bytes }
        val mdat = if (sparse) data { writeInt(8 + frames.sumOf { it.sizeOverride ?: it.bytes.size }); write(ascii("mdat")); write(payload) }
            else box("mdat", payload)
        val result = movie + mdat
        return if (truncated) result.copyOf(result.size - 1) else result
    }

    companion object {
        private const val NON_SYNC = 0x01010000
        private fun ascii(value: String) = value.toByteArray(StandardCharsets.US_ASCII)
        private fun data(block: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { it.block() } }.toByteArray()
        private fun ints(vararg values: Int) = data { values.forEach(::writeInt) }
        private fun box(type: String, vararg payload: ByteArray) = data { writeInt(8 + payload.sumOf { it.size }); write(ascii(type)); payload.forEach(::write) }
    }
}
