package eu.camemake.camevision

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val USB_VENDOR = 0x2207
private const val USB_ACTION = "eu.camemake.camevision.USB_PERMISSION"
private const val T_MANIFEST = 1
private const val T_SYNC = 2
private const val T_VLEFT = 3
private const val T_VRIGHT = 4
private const val T_TSLEFT = 5
private const val T_TSRIGHT = 6
private const val T_IMUL = 7
private const val T_IMUR = 8
private const val T_COMPLETE = 9
private const val T_LOG = 10
private const val T_SID = 11
private const val T_CTRL = 15
private const val T_FILE = 16
private const val T_PREVIEW = 17

data class ImuRead(
    val ax: Double = 0.0,
    val ay: Double = 0.0,
    val az: Double = 0.0,
    val gx: Double = 0.0,
    val gy: Double = 0.0,
    val gz: Double = 0.0,
    val hz: Double = 0.0,
    val present: Boolean = false,
)

data class UsbState(
    val present: Boolean = false,
    val open: Boolean = false,
    val speed: String = "",
    val product: String = "",
    val sensors: Int = 0,
    val imuCount: Int = 0,
    val width: Int = 1920,
    val height: Int = 1200,
    val fps: Int = 50,
    val codec: String = "HEVC",
    val card: Boolean = false,
    val buffer: Boolean = false,
    val recording: Boolean = false,
    val recordStart: Long = 0L,
    val session: String = "",
    val framesLeft: Int = 0,
    val framesRight: Int = 0,
    val error: String = "",
    val note: String = "",
    val savePath: String = "",
    val openedAt: Long = 0L,
    val leftView: ByteArray? = null,
    val rightView: ByteArray? = null,
    val depthView: ByteArray? = null,
    val imu0: ImuRead = ImuRead(),
    val imu1: ImuRead = ImuRead(),
    val imuIso: String = "",
    val imuMonoNs: Long = 0L,
    val imuOdr: Double = 0.0,
)

data class PhoneSession(val id: String, val dir: File, val bytes: Long)

object UsbHub {
    val state = MutableStateFlow(UsbState())
    val sessions = MutableStateFlow<List<PhoneSession>>(emptyList())

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private val running = AtomicBoolean(false)
    private val outQueue = LinkedBlockingQueue<ByteArray>()
    private var writer: Thread? = null
    private var reader: Thread? = null
    private var episode: EpisodeWriter? = null
    private var direct = true
    private var fileOut: FileOutputStream? = null
    private var fileMeta: JSONObject? = null
    private var eyes = "both"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var registered = false

    private val permission = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != USB_ACTION) return
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) open(context.applicationContext, device)
        }
    }

    fun recordingsRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "My Recordings").also { it.mkdirs() }
    }

    fun refresh(context: Context) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { it.vendorId == USB_VENDOR }
        if (device == null) {
            state.value = state.value.copy(present = false, open = false, error = "")
            return
        }
        state.value = state.value.copy(present = true)
        if (manager.hasPermission(device)) {
            if (!state.value.open) open(context.applicationContext, device)
        } else {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                Intent(USB_ACTION).setPackage(context.packageName),
                flags,
            )
            manager.requestPermission(device, pending)
        }
    }

    fun register(context: Context) {
        if (registered) return
        registered = true
        val filter = android.content.IntentFilter(USB_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(permission, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(permission, filter)
        }
    }

    fun setBuffer(on: Boolean) {
        state.value = state.value.copy(buffer = on)
    }

    fun toggleRecord(context: Context) {
        if (state.value.recording) {
            sendCtrl("""{"cmd":"stop"}""")
        } else {
            direct = !state.value.buffer
            val buffer = state.value.buffer
            state.value = state.value.copy(
                recording = true,
                leftView = null,
                rightView = null,
                depthView = null,
                framesLeft = 0,
                framesRight = 0,
                recordStart = System.currentTimeMillis(),
                session = "",
                error = "",
            )
            sendCtrl("""{"cmd":"start","buffer":$buffer}""")
            context.startService(Intent(context, UsbKeepService::class.java))
        }
    }

    fun watch(name: String) {
        eyes = name
        sendCtrl("""{"cmd":"watch","eyes":"$name"}""")
    }

    fun pull(session: String) {
        sendCtrl("""{"cmd":"pull","session":${JSONObject.quote(session)}}""")
    }

    fun refreshLocal(context: Context) {
        val root = recordingsRoot(context)
        sessions.value = root.listFiles().orEmpty()
            .filter { File(it, "episode/manifest.json").isFile || File(it, "episode/cam_left.annexb").isFile }
            .sortedByDescending { it.lastModified() }
            .map { dir ->
                val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                PhoneSession(dir.name, dir, bytes)
            }
    }

    suspend fun upload(context: Context, dir: File) {
        val cfg = ConfigStore(context).config.first()
        if (isServerTemplate(cfg.server)) {
            state.value = state.value.copy(error = "server")
            return
        }
        val server = cfg.server.trim().trimEnd('/')
        val id = dir.name
        val manifest = File(dir, "episode/manifest.json")
        if (manifest.isFile) {
            post(
                "$server/sessions",
                manifest.readText().toRequestBody("application/json".toMediaType()),
                cfg.token,
                id,
                allowConflict = true,
            )
        }
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        for (file in files) {
            val rel = dir.toURI().relativize(file.toURI()).path
            val type = when {
                rel.endsWith(".mp4") -> "video/mp4"
                rel.endsWith(".json") || rel.endsWith(".jsonl") -> "application/json"
                else -> "application/octet-stream"
            }
            post(
                "$server/sessions/$id/files",
                file.asRequestBody(type.toMediaType()),
                cfg.token,
                id,
                path = rel,
            )
        }
        post(
            "$server/sessions/$id/complete",
            """{"id":${JSONObject.quote(id)}}""".toRequestBody("application/json".toMediaType()),
            cfg.token,
            id,
            allowConflict = true,
        )
        state.value = state.value.copy(note = "sent", error = "")
    }

    private fun post(
        url: String,
        body: okhttp3.RequestBody,
        token: String,
        session: String,
        path: String = "",
        allowConflict: Boolean = false,
    ) {
        val builder = Request.Builder().url(url).post(body)
            .header("User-Agent", "CameVision")
            .header("X-CameVision-Product", state.value.product.ifBlank { "CameVision" })
            .header("X-CameVision-Session", session)
        if (path.isNotEmpty()) builder.header("X-CameVision-Path", path)
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful) return
            if (allowConflict && resp.code == 409) return
            throw IllegalStateException("upload ${resp.code}")
        }
    }

    private fun open(context: Context, device: UsbDevice) {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        var chosen: UsbInterface? = null
        var configId = -1
        for (c in 0 until device.configurationCount) {
            val cfg = device.getConfiguration(c)
            for (i in 0 until cfg.interfaceCount) {
                val intf = cfg.getInterface(i)
                if (intf.interfaceClass == 255 && intf.interfaceSubclass == 0x43 && intf.interfaceProtocol == 1) {
                    chosen = intf
                    configId = cfg.id
                }
            }
        }
        val intf = chosen
        if (intf == null) {
            state.value = state.value.copy(present = true, open = false, error = "no phone function")
            return
        }
        val conn = manager.openDevice(device) ?: return
        if (configId >= 0) conn.setConfiguration(device.getConfiguration(configIndex(device, configId)))
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            state.value = state.value.copy(error = "claim failed")
            return
        }
        var inn: UsbEndpoint? = null
        var out: UsbEndpoint? = null
        for (e in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) inn = ep else out = ep
        }
        if (inn == null || out == null) {
            conn.close()
            state.value = state.value.copy(error = "no bulk endpoint")
            return
        }
        closeQuiet()
        connection = conn
        usbInterface = intf
        epIn = inn
        epOut = out
        running.set(true)
        writer = Thread({ writeLoop() }, "camevision-usb-out").also { it.start() }
        reader = Thread({ readLoop(context) }, "camevision-usb-in").also { it.start() }
        state.value = state.value.copy(
            present = true,
            open = true,
            error = "",
            openedAt = System.currentTimeMillis(),
            leftView = null,
            rightView = null,
            depthView = null,
        )
        sendCtrl("""{"cmd":"hello"}""")
        refreshLocal(context)
    }

    private fun configIndex(device: UsbDevice, id: Int): Int {
        for (c in 0 until device.configurationCount) {
            if (device.getConfiguration(c).id == id) return c
        }
        return 0
    }

    private fun sendCtrl(json: String) {
        outQueue.offer(pack(T_CTRL, json.toByteArray()))
    }

    private fun writeLoop() {
        val ep = epOut ?: return
        val conn = connection ?: return
        val mps = ep.maxPacketSize.coerceAtLeast(64)
        while (running.get()) {
            val blob = outQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            var off = 0
            while (off < blob.size && running.get()) {
                val n = minOf(16384, blob.size - off)
                val slice = blob.copyOfRange(off, off + n)
                val sent = conn.bulkTransfer(ep, slice, slice.size, 4000)
                if (sent <= 0) return
                off += sent
            }
            if (blob.isNotEmpty() && blob.size % mps == 0) {
                conn.bulkTransfer(ep, ByteArray(0), 0, 200)
            }
        }
    }

    private fun readLoop(context: Context) {
        val ep = epIn ?: return
        val conn = connection ?: return
        val buf = ByteArray(256 * 1024)
        val parser = StreamParser { kind, flags, payload -> onPacket(context, kind, flags, payload) }
        while (running.get()) {
            val n = conn.bulkTransfer(ep, buf, buf.size, 1000)
            if (n > 0) parser.add(buf, n)
        }
    }

    private fun onPacket(context: Context, kind: Int, flags: Int, payload: ByteArray) {
        when (kind) {
            T_CTRL -> onCtrl(context, payload)
            T_SID -> {
                val sid = payload.toString(Charsets.UTF_8).trim().ifBlank { "session" }
                if (direct) {
                    val dir = File(recordingsRoot(context), sid)
                    episode?.finish()
                    episode = EpisodeWriter(dir).also { it.open() }
                }
                state.value = state.value.copy(recording = true, session = sid, framesLeft = 0, framesRight = 0, recordStart = System.currentTimeMillis(), error = "")
            }
            T_MANIFEST -> if (direct) episode?.manifest(payload)
            T_SYNC -> if (direct) episode?.syncPoint(payload)
            T_VLEFT -> if (direct) episode?.append("cam_left.annexb", payload)
            T_VRIGHT -> if (direct) episode?.append("cam_right.annexb", payload)
            T_TSLEFT -> {
                if (direct) episode?.append("cam_left.timestamps.jsonl", payload)
                bump(left = payload.count { it == '\n'.code.toByte() })
            }
            T_TSRIGHT -> {
                if (direct) episode?.append("cam_right.timestamps.jsonl", payload)
                bump(right = payload.count { it == '\n'.code.toByte() })
            }
            T_IMUL -> if (direct) episode?.append("imu_left.jsonl", payload)
            T_IMUR -> if (direct) episode?.append("imu_right.jsonl", payload)
            T_LOG -> if (direct) episode?.append("session.log", payload)
            T_COMPLETE -> finishDirect(context)
            T_FILE -> onFile(context, flags, payload)
            T_PREVIEW -> {
                if (state.value.recording) return
                if (!completeJpeg(payload)) return
                val now = state.value
                state.value = when (flags) {
                    1 -> now.copy(rightView = payload)
                    2 -> now.copy(depthView = payload)
                    else -> now.copy(leftView = payload)
                }
            }
        }
    }

    private fun completeJpeg(payload: ByteArray): Boolean {
        val n = payload.size
        if (n < 4) return false
        if (payload[0] != 0xFF.toByte() || payload[1] != 0xD8.toByte()) return false
        return payload[n - 2] == 0xFF.toByte() && payload[n - 1] == 0xD9.toByte()
    }

    private fun bump(left: Int = 0, right: Int = 0) {
        val now = state.value
        state.value = now.copy(framesLeft = now.framesLeft + left, framesRight = now.framesRight + right)
    }

    private fun finishDirect(context: Context) {
        val writer = episode
        episode = null
        if (writer != null) {
            Thread({
                runCatching { writer.finish() }
                refreshLocal(context)
            }, "camevision-mux").start()
        }
        state.value = state.value.copy(recording = false, recordStart = 0L, note = "saved")
    }

    private fun onCtrl(context: Context, payload: ByteArray) {
        val obj = runCatching { JSONObject(payload.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        when (obj.optString("t")) {
            "hello" -> {
                state.value = state.value.copy(
                    speed = obj.optString("speed"),
                    product = obj.optString("product"),
                    sensors = obj.optInt("sensors"),
                    imuCount = obj.optInt("imu"),
                    width = obj.optInt("width", 1920),
                    height = obj.optInt("height", 1200),
                    fps = obj.optInt("fps", 50),
                    codec = obj.optString("codec", "hevc").uppercase(),
                    card = obj.optBoolean("card"),
                    error = "",
                )
                sendCtrl("""{"cmd":"watch","eyes":"$eyes"}""")
            }
            "imu" -> state.value = state.value.copy(
                imuIso = obj.optString("iso"),
                imuMonoNs = obj.optLong("mono_ns"),
                imuOdr = obj.optDouble("odr_hz"),
                imu0 = readImu(obj.optJSONObject("imu0")),
                imu1 = readImu(obj.optJSONObject("imu1")),
            )
            "ack" -> {
                val cmd = obj.optString("cmd")
                val ok = obj.optBoolean("ok")
                if (!ok) {
                    state.value = state.value.copy(error = obj.optString("error").ifBlank { cmd }, recording = false)
                } else if (cmd == "start") {
                    state.value = state.value.copy(
                        recording = true,
                        session = obj.optString("session"),
                        savePath = obj.optString("path"),
                        recordStart = System.currentTimeMillis(),
                        framesLeft = 0,
                        framesRight = 0,
                        error = "",
                    )
                } else if (cmd == "stop") {
                    state.value = state.value.copy(
                        recording = false,
                        recordStart = 0L,
                        session = obj.optString("session"),
                        savePath = obj.optString("path").ifBlank { state.value.savePath },
                    )
                    if (obj.optBoolean("buffer")) pull(obj.optString("session"))
                    else if (episode == null) finishDirect(context)
                }
            }
            "file" -> {
                runCatching { fileOut?.close() }
                fileOut = null
                fileMeta = obj
                val session = obj.optString("session")
                val rel = obj.optString("path")
                if (session.isNotBlank() && rel.isNotBlank()) {
                    val dest = File(recordingsRoot(context), "$session/$rel")
                    dest.parentFile?.mkdirs()
                    fileOut = FileOutputStream(dest)
                }
            }
            "pull_done" -> {
                refreshLocal(context)
                state.value = state.value.copy(note = "downloaded", recording = false)
            }
        }
    }

    private fun onFile(context: Context, flags: Int, payload: ByteArray) {
        if (fileMeta == null) return
        fileOut?.write(payload)
        if (flags and 1 == 0) return
        runCatching { fileOut?.flush(); fileOut?.close() }
        fileOut = null
        fileMeta = null
        refreshLocal(context)
    }

    fun closeQuiet() {
        running.set(false)
        runCatching { connection?.releaseInterface(usbInterface) }
        runCatching { connection?.close() }
        connection = null
        usbInterface = null
        epIn = null
        epOut = null
    }

    private fun pack(kind: Int, payload: ByteArray, flags: Int = 0): ByteArray {
        val head = byteArrayOf(
            'E'.code.toByte(), 'G'.code.toByte(), 'O'.code.toByte(), 'R'.code.toByte(),
            1, kind.toByte(),
            (flags shr 8).toByte(), (flags and 0xFF).toByte(),
            (payload.size shr 24).toByte(),
            (payload.size shr 16).toByte(),
            (payload.size shr 8).toByte(),
            (payload.size and 0xFF).toByte(),
        )
        return head + payload
    }
}

fun readImu(obj: JSONObject?): ImuRead {
    if (obj == null || obj.length() == 0) return ImuRead()
    return ImuRead(
        ax = obj.optDouble("ax"),
        ay = obj.optDouble("ay"),
        az = obj.optDouble("az"),
        gx = obj.optDouble("gx"),
        gy = obj.optDouble("gy"),
        gz = obj.optDouble("gz"),
        hz = obj.optDouble("read_hz"),
        present = true,
    )
}

private class StreamParser(private val onPacket: (Int, Int, ByteArray) -> Unit) {
    private var raw = ByteArray(1 shl 20)
    private var len = 0

    fun add(src: ByteArray, n: Int) {
        if (len + n > raw.size) {
            raw = raw.copyOf(maxOf(raw.size * 2, len + n))
        }
        System.arraycopy(src, 0, raw, len, n)
        len += n
        var i = 0
        while (i + 12 <= len) {
            if (raw[i] != 'E'.code.toByte() || raw[i + 1] != 'G'.code.toByte() ||
                raw[i + 2] != 'O'.code.toByte() || raw[i + 3] != 'R'.code.toByte()
            ) {
                i += 1
                continue
            }
            val kind = raw[i + 5].toInt() and 0xFF
            val flags = ((raw[i + 6].toInt() and 0xFF) shl 8) or (raw[i + 7].toInt() and 0xFF)
            val size = ((raw[i + 8].toInt() and 0xFF) shl 24) or
                ((raw[i + 9].toInt() and 0xFF) shl 16) or
                ((raw[i + 10].toInt() and 0xFF) shl 8) or
                (raw[i + 11].toInt() and 0xFF)
            if (size < 0 || size > 8_000_000) {
                i += 1
                continue
            }
            if (i + 12 + size > len) break
            onPacket(kind, flags, raw.copyOfRange(i + 12, i + 12 + size))
            i += 12 + size
        }
        if (i > 0) {
            System.arraycopy(raw, i, raw, 0, len - i)
            len -= i
        }
    }
}
