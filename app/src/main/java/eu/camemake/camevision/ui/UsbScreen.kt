package eu.camemake.camevision.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import eu.camemake.camevision.ImuRead
import eu.camemake.camevision.PhoneSession
import eu.camemake.camevision.R
import eu.camemake.camevision.UsbHub
import eu.camemake.camevision.UsbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

private enum class Eyes { Left, Both, Right, Depth }

@Composable
fun UsbScreen() {
    val context = LocalContext.current
    val usb by UsbHub.state.collectAsState()
    val sessions by UsbHub.sessions.collectAsState()
    var eyes by remember { mutableStateOf(Eyes.Both) }
    var viewOpen by remember { mutableStateOf(false) }
    var imuOpen by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<File?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        UsbHub.register(context.applicationContext)
        UsbHub.refresh(context)
        UsbHub.refreshLocal(context)
        onDispose { }
    }
    LaunchedEffect(usb.present, usb.open) {
        if (usb.present && !usb.open) UsbHub.refresh(context)
    }
    LaunchedEffect(eyes, usb.open, usb.recording) {
        if (!usb.open || usb.recording) return@LaunchedEffect
        val name = when (eyes) {
            Eyes.Left -> "left"
            Eyes.Right -> "right"
            Eyes.Depth -> "depth"
            Eyes.Both -> "both"
        }
        UsbHub.watch(name)
    }
    LaunchedEffect(usb.recording) {
        if (usb.recording) viewOpen = false
        while (usb.recording) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    BackHandler(enabled = viewOpen || imuOpen) {
        viewOpen = false
        imuOpen = false
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                cameraTitle(usb),
                color = CameInk,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 26.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(statusLine(usb), color = CameMuted, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
            if (usb.error.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(errorText(usb.error), color = CamePink, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            if (usb.recording) {
                Text(
                    stringResource(R.string.usb_live_paused),
                    color = CameInk,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                EyeSwitch(eyes, usb.sensors) { eyes = it }
                Spacer(Modifier.height(10.dp))
                PreviewPane(usb, eyes, Modifier.clickable(enabled = usb.open) { viewOpen = true })
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.usb_open_view),
                    color = CameTeal,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(enabled = usb.open) { viewOpen = true },
                )
            }
            Spacer(Modifier.height(16.dp))
            ImuRow(usb) { imuOpen = true }
            Spacer(Modifier.height(22.dp))
            RecordButton(recording = usb.recording && usb.open) {
                if (usb.open) UsbHub.toggleRecord(context) else UsbHub.refresh(context)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (usb.recording) stringResource(R.string.stop_recording) else stringResource(R.string.start_recording),
                color = CameInk,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text(
                stringResource(R.string.usb_record_line, usb.width, usb.height, usb.codec, usb.fps),
                color = CameMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                if (usb.sensors >= 2) stringResource(R.string.usb_record_eyes_two) else stringResource(R.string.usb_record_eyes_one),
                color = CameMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            if (usb.recording && usb.recordStart > 0L) {
                Spacer(Modifier.height(6.dp))
                Text(
                    formatElapsed(now - usb.recordStart),
                    color = CamePink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Text(
                    stringResource(R.string.usb_frames, usb.framesLeft, usb.framesRight),
                    color = CameInk,
                    fontSize = 13.sp,
                )
            }
            if (usb.recording) {
                Spacer(Modifier.height(12.dp))
                RecordingPlace(usb)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.usb_buffer), color = CameInk, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.usb_buffer_help), color = CameMuted, fontSize = 13.sp)
                }
                Switch(
                    checked = usb.buffer,
                    onCheckedChange = { if (!usb.recording) UsbHub.setBuffer(it) },
                    enabled = usb.open && !usb.recording,
                )
            }
            playing?.let { dir ->
                Spacer(Modifier.height(18.dp))
                Text(dir.name, color = CameInk, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                Text(phonePlace(dir), color = CameMuted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                EyePlayer(File(dir, "episode/cam_left.mp4"), stringResource(R.string.usb_eye_left))
                Spacer(Modifier.height(8.dp))
                EyePlayer(File(dir, "episode/cam_right.mp4"), stringResource(R.string.usb_eye_right))
            }
            Spacer(Modifier.height(22.dp))
            PlaceCard(UsbHub.recordingsRoot(context))
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.usb_sessions),
                color = CameInk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (sessions.isEmpty()) {
                Text(stringResource(R.string.usb_none), color = CameMuted, fontSize = 14.sp, modifier = Modifier.fillMaxWidth())
            }
            sessions.forEach { session ->
                Spacer(Modifier.height(12.dp))
                SessionCard(
                    session = session,
                    onPlay = { playing = session.dir },
                    onUpload = {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { UsbHub.upload(context, session.dir) }
                            }.onFailure { err ->
                                UsbHub.state.value = UsbHub.state.value.copy(error = err.message ?: "upload")
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(28.dp))
        }
        if (viewOpen) {
            ViewOverlay(usb, eyes, { eyes = it }) { viewOpen = false }
        }
        if (imuOpen) {
            ImuOverlay(usb) { imuOpen = false }
        }
    }
}

@Composable
private fun EyeSwitch(eyes: Eyes, sensors: Int, onSelect: (Eyes) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, CameLine, RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        EyeChip(stringResource(R.string.usb_eye_left), eyes == Eyes.Left, Modifier.weight(1f)) { onSelect(Eyes.Left) }
        if (sensors != 1) {
            EyeChip(stringResource(R.string.usb_eye_both), eyes == Eyes.Both, Modifier.weight(1f)) { onSelect(Eyes.Both) }
        }
        if (sensors != 1) {
            EyeChip(stringResource(R.string.usb_eye_right), eyes == Eyes.Right, Modifier.weight(1f)) { onSelect(Eyes.Right) }
            EyeChip(stringResource(R.string.usb_eye_depth), eyes == Eyes.Depth, Modifier.weight(1f)) { onSelect(Eyes.Depth) }
        }
    }
}

@Composable
private fun EyeChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CameTeal else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else CameInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun PreviewPane(usb: UsbState, eyes: Eyes, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1C1C))
            .padding(8.dp),
    ) {
        if (!usb.open) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                if (usb.present) {
                    OpeningMark(stringResource(R.string.usb_connecting))
                } else {
                    Text(stringResource(R.string.usb_waiting), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
            return
        }
        if (eyes == Eyes.Depth) {
            JpegStill(usb.depthView, stringResource(R.string.usb_eye_depth), openedAt = usb.openedAt)
            return
        }
        if (eyes != Eyes.Right) JpegStill(usb.leftView, stringResource(R.string.usb_eye_left), openedAt = usb.openedAt)
        if (eyes == Eyes.Both) Spacer(Modifier.height(6.dp))
        if (eyes != Eyes.Left && usb.sensors != 1) JpegStill(usb.rightView, stringResource(R.string.usb_eye_right), openedAt = usb.openedAt)
    }
}

@Composable
private fun JpegStill(
    bytes: ByteArray?,
    label: String,
    frameHeight: androidx.compose.ui.unit.Dp = 168.dp,
    openedAt: Long = 0L,
) {
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }?.asImageBitmap()
    }
    Box(Modifier.fillMaxWidth().height(frameHeight).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = label,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = -1f
                    scaleY = -1f
                },
                contentScale = ContentScale.Fit,
            )
        } else {
            OpeningMark(openingText(openedAt))
        }
        Text(
            label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x88000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun openingText(openedAt: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(openedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(400)
        }
    }
    val slow = openedAt > 0L && now - openedAt > 4000L
    return stringResource(if (slow) R.string.usb_opening_still else R.string.usb_opening)
}

@Composable
private fun OpeningMark(message: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 3.dp,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(message, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ImuRow(usb: UsbState, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, CameLine, RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.usb_imu_title), color = CameInk, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.usb_imu_open), color = CameTeal, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        ImuLine(stringResource(R.string.usb_imu0), usb.imu0)
        if (usb.sensors != 1) {
            Spacer(Modifier.height(4.dp))
            ImuLine(stringResource(R.string.usb_imu1), usb.imu1)
        }
    }
}

@Composable
private fun ImuLine(name: String, imu: ImuRead) {
    val text = if (!imu.present) {
        stringResource(R.string.usb_imu_waiting)
    } else {
        stringResource(R.string.usb_imu_brief, imu.ax, imu.ay, imu.az, imu.gx, imu.gy, imu.gz)
    }
    Text("$name  $text", color = CameMuted, fontSize = 12.sp)
}

@Composable
private fun ViewOverlay(usb: UsbState, eyes: Eyes, onEyes: (Eyes) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF111111))
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.usb_live), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                stringResource(R.string.close),
                color = Color.White,
                modifier = Modifier.clickable(onClick = onClose).padding(8.dp),
            )
        }
        EyeSwitch(eyes, usb.sensors, onEyes)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (eyes == Eyes.Depth) {
                JpegStill(usb.depthView, stringResource(R.string.usb_eye_depth), 360.dp, usb.openedAt)
            } else {
                if (eyes != Eyes.Right) JpegStill(usb.leftView, stringResource(R.string.usb_eye_left), 280.dp, usb.openedAt)
                if (eyes == Eyes.Both) Spacer(Modifier.height(8.dp))
                if (eyes != Eyes.Left && usb.sensors != 1) JpegStill(usb.rightView, stringResource(R.string.usb_eye_right), 280.dp, usb.openedAt)
            }
        }
    }
}

@Composable
private fun ImuOverlay(usb: UsbState, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CameBg)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.usb_imu_title), color = CameInk, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
            Text(stringResource(R.string.close), color = CameTeal, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onClose))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.usb_imu_meta, usb.imuOdr, usb.imuIso.ifBlank { "—" }),
            color = CameMuted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(14.dp))
        ImuCard(stringResource(R.string.usb_imu0), stringResource(R.string.usb_cam0), usb.imu0, usb.imuMonoNs)
        if (usb.sensors != 1) {
            Spacer(Modifier.height(12.dp))
            ImuCard(stringResource(R.string.usb_imu1), stringResource(R.string.usb_cam1), usb.imu1, usb.imuMonoNs)
        }
    }
}

@Composable
private fun ImuCard(name: String, camera: String, imu: ImuRead, monoNs: Long) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, CameLine, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(name, color = CameInk, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(camera, color = CameMuted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        if (!imu.present) {
            Text(stringResource(R.string.usb_imu_waiting), color = CameMuted)
            return
        }
        MetaRow(stringResource(R.string.usb_accel), "x ${fmt(imu.ax)}   y ${fmt(imu.ay)}   z ${fmt(imu.az)} m/s²")
        MetaRow(stringResource(R.string.usb_gyro), "x ${fmt(imu.gx)}   y ${fmt(imu.gy)}   z ${fmt(imu.gz)} °/s")
        MetaRow(stringResource(R.string.usb_rate), String.format(Locale.US, "%.0f Hz", imu.hz))
        MetaRow(stringResource(R.string.usb_time), monoNs.toString())
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Text(label, color = CameMuted, fontSize = 12.sp)
    Text(value, color = CameInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EyePlayer(file: File, label: String) {
    if (!file.isFile) return
    Text(label, color = CameMuted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.fromFile(file))
                setOnPreparedListener { player ->
                    player.isLooping = true
                    start()
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(180.dp),
    )
}

@Composable
private fun RecordingPlace(usb: UsbState) {
    val context = LocalContext.current
    val folder = if (usb.session.isBlank()) {
        UsbHub.recordingsRoot(context)
    } else {
        File(UsbHub.recordingsRoot(context), usb.session)
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (usb.session.isBlank()) {
                stringResource(R.string.usb_writing_wait)
            } else {
                stringResource(R.string.usb_writing, usb.session)
            },
            color = CameInk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(phonePlace(folder), color = CameMuted, fontSize = 13.sp, lineHeight = 18.sp)
        if (usb.buffer) {
            Spacer(Modifier.height(6.dp))
            if (usb.savePath.isNotBlank()) {
                Text(stringResource(R.string.usb_sd_path, usb.savePath), color = CameMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Text(stringResource(R.string.usb_sd_then), color = CameMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun PlaceCard(root: File) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, CameLine, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(stringResource(R.string.usb_tree_title), color = CameInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.usb_tree_body), color = CameMuted, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.usb_tree_example),
            color = CameInk,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(phonePlace(root), color = CameInk, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            if (copied) stringResource(R.string.usb_copied) else stringResource(R.string.usb_copy_path),
            color = CameTeal,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable {
                copyText(context, root.absolutePath)
                copied = true
            },
        )
    }
}

@Composable
private fun SessionCard(session: PhoneSession, onPlay: () -> Unit, onUpload: () -> Unit) {
    val context = LocalContext.current
    var copied by remember(session.id) { mutableStateOf(false) }
    val files = remember(session.dir, session.bytes) { sessionFiles(session.dir) }
    val recordedOn = remember(session.dir, session.bytes) { cameraPath(session.dir) }
    Column(Modifier.fillMaxWidth()) {
        Text(session.id, color = CameInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(phonePlace(session.dir), color = CameMuted, fontSize = 12.sp, lineHeight = 16.sp)
        if (recordedOn.isNotBlank()) {
            Text(stringResource(R.string.usb_recorded_on, recordedOn), color = CameMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.usb_files), color = CameInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        files.forEach { (rel, bytes) ->
            Text(
                "$rel   ${formatBytes(bytes)}",
                color = CameMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(
                stringResource(R.string.usb_view),
                color = CameTeal,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp).clickable(onClick = onPlay),
            )
            Text(
                stringResource(R.string.usb_upload),
                color = CameTeal,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp).clickable(onClick = onUpload),
            )
            Text(
                if (copied) stringResource(R.string.usb_copied) else stringResource(R.string.usb_copy_path),
                color = CameTeal,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp).clickable {
                    copyText(context, session.dir.absolutePath)
                    copied = true
                },
            )
        }
    }
}

@Composable
private fun phonePlace(file: File): String = stringResource(R.string.usb_internal, storageTail(file))

private fun storageTail(file: File): String {
    val abs = file.absolutePath.replace('\\', '/')
    val mark = "/Android/data/"
    val index = abs.indexOf(mark)
    return if (index >= 0) abs.substring(index) else abs
}

private fun sessionFiles(dir: File): List<Pair<String, Long>> {
    if (!dir.isDirectory) return emptyList()
    return dir.walkTopDown()
        .filter { it.isFile }
        .map { dir.toURI().relativize(it.toURI()).path to it.length() }
        .sortedBy { it.first }
        .toList()
}

private fun cameraPath(dir: File): String {
    val man = File(dir, "episode/manifest.json")
    if (!man.isFile) return ""
    return runCatching { JSONObject(man.readText()).optString("path") }.getOrDefault("")
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("CameVision", text))
}

@Composable
private fun cameraTitle(usb: UsbState): String {
    if (!usb.open) return stringResource(R.string.usb_title)
    return if (usb.sensors >= 2) stringResource(R.string.usb_name_ego) else stringResource(R.string.usb_name_single)
}

@Composable
private fun statusLine(usb: UsbState): String {
    if (!usb.present) return stringResource(R.string.usb_waiting)
    if (!usb.open) return stringResource(R.string.usb_connecting)
    val link = if (usb.speed.startsWith("super")) stringResource(R.string.usb_speed_super) else stringResource(R.string.usb_speed_high)
    return stringResource(R.string.usb_ready, link, usb.width, usb.height, usb.fps)
}

@Composable
private fun errorText(code: String): String = when (code) {
    "no card" -> stringResource(R.string.usb_no_card)
    "server" -> stringResource(R.string.err_server_template)
    "no phone function" -> stringResource(R.string.usb_no_function)
    else -> code
}

private fun fmt(value: Double): String = String.format(Locale.US, "%+.2f", value)
