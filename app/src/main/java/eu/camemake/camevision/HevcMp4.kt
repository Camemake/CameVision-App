package eu.camemake.camevision

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Same Annex-B to MP4 path the camera and the PC writer use. */
object HevcMp4 {
    fun write(src: File, dest: File, codec: String, width: Int, height: Int, fps: Double) {
        val samples = mutableListOf<ByteArray>()
        val keys = mutableListOf<Int>()
        var vps = ByteArray(0)
        var sps = ByteArray(0)
        var pps = ByteArray(0)
        var pending = ByteArray(0)
        var pendingKey = 0
        val hevc = codec == "hevc"

        fun flush() {
            if (pending.isNotEmpty()) {
                samples.add(pending)
                keys.add(pendingKey)
            }
            pending = ByteArray(0)
            pendingKey = 0
        }

        for (nal in splitNals(src.readBytes())) {
            if (hevc) {
                val ntype = (nal[0].toInt() shr 1) and 0x3F
                when {
                    ntype == 32 -> vps = nal
                    ntype == 33 -> sps = nal
                    ntype == 34 -> pps = nal
                    ntype == 35 -> flush()
                    ntype < 32 -> {
                        flush()
                        pending = lengthPrefixed(nal)
                        pendingKey = if (ntype == 19 || ntype == 20 || ntype == 21) 1 else 0
                    }
                    else -> pending += lengthPrefixed(nal)
                }
            } else {
                val ntype = nal[0].toInt() and 0x1F
                when {
                    ntype == 7 -> sps = nal
                    ntype == 8 -> pps = nal
                    ntype == 9 -> flush()
                    ntype == 1 || ntype == 5 -> {
                        flush()
                        pending = lengthPrefixed(nal)
                        pendingKey = if (ntype == 5) 1 else 0
                    }
                    else -> pending += lengthPrefixed(nal)
                }
            }
        }
        flush()
        if (samples.isEmpty() || sps.isEmpty() || pps.isEmpty() || (hevc && vps.isEmpty())) {
            throw IllegalStateException("incomplete $codec bitstream")
        }
        val timescale = 90_000
        val step = maxOf(1, kotlin.math.round(timescale / maxOf(1.0, fps)).toInt())
        val duration = step * samples.size
        val extra = if (hevc) {
            box("hvcC", hvcc(vps, sps, pps)) + colr()
        } else {
            box("avcC", avcc(sps, pps)) + colr()
        }
        val fmt = if (hevc) "hvc1" else "avc1"
        val visual = visualSample(width, height, extra)
        val stsd = box("stsd", beInt(0) + beInt(1) + box(fmt, visual))
        val stts = box("stts", beInt(0) + beInt(1) + beInt(samples.size) + beInt(step))
        var sync = keys.mapIndexedNotNull { index, key -> if (key == 1) index + 1 else null }
        if (sync.isEmpty()) sync = listOf(1)
        val stss = box(
            "stss",
            ByteBuffer.allocate(8 + sync.size * 4).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0)
                putInt(sync.size)
                sync.forEach { putInt(it) }
            }.array(),
        )
        val stsc = box("stsc", beInt(0) + beInt(1) + beInt(1) + beInt(samples.size) + beInt(1))
        val stsz = box(
            "stsz",
            ByteBuffer.allocate(12 + samples.size * 4).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0)
                putInt(0)
                putInt(samples.size)
                samples.forEach { putInt(it.size) }
            }.array(),
        )
        val stco = box("stco", beInt(0) + beInt(1) + beInt(0))
        val minf = minf(stsd, stts, stss, stsc, stsz, stco)
        val mdhd = box("mdhd", beInt(0) + beInt(0) + beInt(0) + beInt(timescale) + beInt(duration) + beShort(0x55C4) + beShort(0))
        val hdlr = box("hdlr", beInt(0) + ByteArray(4) + "vide".toByteArray() + ByteArray(12) + "VideoHandler".toByteArray() + byteArrayOf(0))
        val mdia = box("mdia", mdhd + hdlr + minf)
        val tkhd = tkhd(duration, width, height)
        val trak = box("trak", tkhd + mdia)
        val mvhd = mvhd(timescale, duration)
        val ftyp = box("ftyp", "isom".toByteArray() + beInt(0x200) + "isomiso2mp41".toByteArray())
        var moov = box("moov", mvhd + trak)
        val chunk = ftyp.size + moov.size + 8
        val stco2 = box("stco", beInt(0) + beInt(1) + beInt(chunk))
        val minf2 = minf(stsd, stts, stss, stsc, stsz, stco2)
        val mdia2 = box("mdia", mdhd + hdlr + minf2)
        moov = box("moov", mvhd + box("trak", tkhd + mdia2))
        var payloadSize = 0
        for (sample in samples) payloadSize += sample.size
        dest.outputStream().use { out ->
            out.write(ftyp)
            out.write(moov)
            out.write(beInt(8 + payloadSize))
            out.write("mdat".toByteArray())
            for (sample in samples) out.write(sample)
        }
    }

    fun splitNals(buf: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < buf.size - 3) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 0.toByte() && buf[i + 3] == 1.toByte()) {
                starts.add(i)
                i += 4
                continue
            }
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && buf[i + 2] == 1.toByte()) {
                starts.add(i)
                i += 3
                continue
            }
            i += 1
        }
        return starts.mapIndexed { index, start ->
            val mark = if (start + 3 < buf.size && buf[start + 2] == 0.toByte()) 4 else 3
            val end = if (index + 1 < starts.size) starts[index + 1] else buf.size
            buf.copyOfRange(start + mark, end)
        }.filter { it.isNotEmpty() }
    }

    private fun lengthPrefixed(nal: ByteArray) = beInt(nal.size) + nal

    private fun hvcc(vps: ByteArray, sps: ByteArray, pps: ByteArray): ByteArray {
        val rbsp = unescape(if (sps.size > 2) sps.copyOfRange(2, sps.size) else sps)
        val ptl = if (rbsp.size >= 13) rbsp.copyOfRange(1, 13) else ByteArray(12)
        return byteArrayOf(1) + ptl +
            byteArrayOf(0xF0.toByte(), 0, 0xFC.toByte(), 0xFD.toByte(), 0xF8.toByte(), 0xF8.toByte(), 0, 0, 0x4F, 3) +
            nalArray(32, vps) + nalArray(33, sps) + nalArray(34, pps)
    }

    private fun nalArray(kind: Int, nal: ByteArray): ByteArray {
        return byteArrayOf((kind or 0x80).toByte()) + beShort(1) + beShort(nal.size) + nal
    }

    private fun avcc(sps: ByteArray, pps: ByteArray): ByteArray {
        return byteArrayOf(1, sps[1], sps[2], sps[3], 0xFF.toByte(), 0xE1.toByte()) +
            beShort(sps.size) + sps + byteArrayOf(1) + beShort(pps.size) + pps
    }

    private fun unescape(src: ByteArray): ByteArray {
        val out = ArrayList<Byte>(src.size)
        var i = 0
        while (i < src.size) {
            if (i + 2 < src.size && src[i] == 0.toByte() && src[i + 1] == 0.toByte() && src[i + 2] == 3.toByte()) {
                out.add(0)
                out.add(0)
                i += 3
                continue
            }
            out.add(src[i])
            i += 1
        }
        return out.toByteArray()
    }

    private fun colr() = box("colr", "nclx".toByteArray() + beShort(1) + beShort(1) + beShort(1) + byteArrayOf(0x80.toByte()))

    private fun visualSample(width: Int, height: Int, extra: ByteArray): ByteArray {
        val name = ByteArray(32)
        val text = "\u000cCameVision".toByteArray()
        text.copyInto(name)
        return ByteArray(6) + beShort(1) + beShort(0) + beShort(0) + ByteArray(12) +
            beShort(width) + beShort(height) + beInt(0x00480000) + beInt(0x00480000) + beInt(0) +
            beShort(1) + name + beShort(0x0018) + beShort(-1) + extra
    }

    private fun minf(stsd: ByteArray, stts: ByteArray, stss: ByteArray, stsc: ByteArray, stsz: ByteArray, stco: ByteArray): ByteArray {
        val stbl = box("stbl", stsd + stts + stss + stsc + stsz + stco)
        val vmhd = box("vmhd", beShort(1) + beShort(0) + ByteArray(6))
        val dref = box("dref", beInt(0) + beInt(1) + box("url ", beInt(1)))
        return box("minf", vmhd + box("dinf", dref) + stbl)
    }

    private fun tkhd(duration: Int, width: Int, height: Int): ByteArray {
        val matrix = intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).fold(ByteArray(0)) { acc, n -> acc + beInt(n) }
        return box(
            "tkhd",
            beInt(7) + beInt(0) + beInt(0) + beInt(1) + beInt(0) + beInt(duration) +
                beInt(0) + beInt(0) + beShort(0) + beShort(0) + beShort(0) + beShort(0) +
                matrix + beInt(width shl 16) + beInt(height shl 16),
        )
    }

    private fun mvhd(timescale: Int, duration: Int): ByteArray {
        val matrix = intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).fold(ByteArray(0)) { acc, n -> acc + beInt(n) }
        return box(
            "mvhd",
            beInt(0) + beInt(0) + beInt(0) + beInt(timescale) + beInt(duration) + beInt(0x00010000) +
                beShort(0x0100) + beShort(0) + beInt(0) + beInt(0) + matrix +
                beInt(0) + beInt(0) + beInt(0) + beInt(0) + beInt(0) + beInt(0) + beInt(2),
        )
    }

    private fun box(tag: String, payload: ByteArray) = beInt(8 + payload.size) + tag.toByteArray() + payload

    private fun beInt(value: Int): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(value)
        return buf.array()
    }

    private fun beShort(value: Int): ByteArray {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(value.toShort())
        return buf.array()
    }
}
