package eu.camemake.camevision

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Phone copy of My Recordings/<session>/episode and synced.
 * Direct USB record writes this tree. A microSD buffer is downloaded into the same tree.
 */
class EpisodeWriter(private val root: File) {
    private val episode = File(root, "episode")
    private val synced = File(root, "synced")
    private val files = HashMap<String, FileOutputStream>()
    var codec = "hevc"
    var width = 1920
    var height = 1200
    var fps = 50.0
    var sessionId = root.name

    fun open() {
        episode.mkdirs()
        synced.mkdirs()
    }

    fun manifest(payload: ByteArray) {
        val text = payload.toString(Charsets.UTF_8)
        val obj = JSONObject(text)
        sessionId = obj.optString("session_id", sessionId)
        val video = obj.optJSONObject("video")
        if (video != null) {
            codec = video.optString("codec", codec)
            width = video.optInt("width", width)
            height = video.optInt("height", height)
            fps = video.optDouble("fps_target", fps)
        }
        File(episode, "manifest.json").writeText(obj.toString(2) + "\n")
    }

    fun syncPoint(payload: ByteArray) {
        val obj = JSONObject(payload.toString(Charsets.UTF_8))
        File(episode, "sync_point.json").writeText(obj.toString(2) + "\n")
    }

    fun append(name: String, payload: ByteArray) {
        val out = files.getOrPut(name) {
            FileOutputStream(File(episode, name), true)
        }
        out.write(payload)
    }

    fun file(relative: String, payload: ByteArray) {
        val dest = File(root, relative)
        dest.parentFile?.mkdirs()
        dest.writeBytes(payload)
    }

    fun finish() {
        files.values.forEach { runCatching { it.flush(); it.close() } }
        files.clear()
        for ((side, mp4) in listOf("left" to "cam_left.mp4", "right" to "cam_right.mp4")) {
            val raw = File(episode, "cam_$side.annexb")
            if (!raw.isFile || raw.length() < 32) continue
            val ts = File(episode, "cam_$side.timestamps.jsonl")
            val rate = fpsFrom(ts, fps)
            runCatching {
                HevcMp4.write(raw, File(episode, mp4), codec, width, height, rate)
            }.onFailure { err ->
                File(episode, "session.log").appendText("mp4 $mp4 ${err.message}\n")
            }
        }
        writeFrameMap()
        val man = File(episode, "manifest.json")
        if (man.isFile) {
            val obj = JSONObject(man.readText())
            obj.put("session_complete", true)
            obj.put("storage", obj.optString("storage").ifBlank { "phone" })
            obj.put("phone_path", root.absolutePath)
            obj.put("layout", "My Recordings/<session>/episode + synced")
            obj.put("closed_unix", System.currentTimeMillis() / 1000.0)
            val listed = JSONArray()
            episode.listFiles().orEmpty()
                .filter { it.isFile && !it.name.endsWith(".tmp") }
                .sortedBy { it.name }
                .forEach { file ->
                    listed.put(
                        JSONObject()
                            .put("path", file.name)
                            .put("bytes", file.length())
                            .put("sha256", sha256(file)),
                    )
                }
            obj.put("files", listed)
            man.writeText(obj.toString(2) + "\n")
        }
    }

    private fun writeFrameMap() {
        val left = loadTs(File(episode, "cam_left.timestamps.jsonl"))
        val right = loadTs(File(episode, "cam_right.timestamps.jsonl")).associateBy {
            it.optInt("trigger_index", -1)
        }
        val map = File(synced, "frame_map.jsonl")
        var matched = 0
        val dts = ArrayList<Int>()
        map.bufferedWriter().use { out ->
            for (row in left) {
                val ti = row.optInt("trigger_index", -1)
                val other = right[ti]
                val rec = JSONObject()
                rec.put("trigger_index", ti)
                rec.put("left_frame", row.opt("frame_index"))
                rec.put("right_frame", if (other == null) JSONObject.NULL else other.opt("frame_index"))
                rec.put("t_left", row.opt("timestamp_or_counter"))
                rec.put("t_right", if (other == null) JSONObject.NULL else other.opt("timestamp_or_counter"))
                if (other != null) {
                    matched += 1
                    val dt = ((other.optLong("timestamp_or_counter") - row.optLong("timestamp_or_counter")) / 1000).toInt()
                    rec.put("dt_us", dt)
                    dts.add(kotlin.math.abs(dt))
                }
                out.write(rec.toString())
                out.write("\n")
            }
        }
        val report = JSONObject()
        report.put("left_frames", left.size)
        report.put("right_frames", right.size)
        report.put("matched", matched)
        report.put("unmatched", left.size - matched)
        report.put("dt_us_max", dts.maxOrNull() ?: 0)
        report.put("dt_us_mean", if (dts.isEmpty()) 0 else dts.sum() / dts.size)
        File(synced, "sync_report.json").writeText(report.toString(2) + "\n")
    }

    private fun loadTs(path: File): List<JSONObject> {
        if (!path.isFile) return emptyList()
        return path.readLines().mapNotNull { line ->
            val text = line.trim()
            if (text.isEmpty()) null else runCatching { JSONObject(text) }.getOrNull()
        }
    }

    private fun fpsFrom(path: File, fallback: Double): Double {
        val times = loadTs(path).map { it.optLong("timestamp_or_counter") }.filter { it > 0 }
        if (times.size < 2) return fallback
        val span = (times.last() - times.first()) / 1_000_000_000.0
        if (span > 0.05) return ((times.size - 1) / span).coerceIn(1.0, 60.0)
        return fallback
    }

    private fun sha256(path: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
