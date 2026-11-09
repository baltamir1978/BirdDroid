package es.altamirano.bird.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box as Stack
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import es.altamirano.bird.PhotoInfo
import es.altamirano.bird.PhotoResult
import es.altamirano.bird.Photos
import es.altamirano.bird.R
import es.altamirano.bird.Subject
import es.altamirano.bird.core.Box
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** El proceso de una foto: de abrirla (siempre pasa por el encuadre, como en iOS) al resultado. */
@Stable
class PhotoSession(private val context: Context, private val scope: CoroutineScope) {
    /** La foto ya enviada a identificar. */
    var photo by mutableStateOf<PhotoInfo?>(null); private set
    /** La foto que se está encuadrando: si hay una, se ve la pantalla de encuadre. */
    var framing by mutableStateOf<PhotoInfo?>(null); private set
    /** Lo que encontró la búsqueda automática. */
    var subject by mutableStateOf<Subject?>(null); private set
    var detecting by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var working by mutableStateOf(false); private set
    var result by mutableStateOf<PhotoResult?>(null); private set
    var usedRegion by mutableStateOf(false); private set
    @get:StringRes var error by mutableStateOf<Int?>(null); private set

    private var search: Deferred<Subject?>? = null
    /** La foto a la que corresponden [search] y [subject]. */
    private var searched: PhotoInfo? = null

    fun open(uri: Uri) {
        scope.launch {
            loading = true; error = null
            val info = try { Photos.load(context, uri) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "No se pudo abrir", e); null }
            loading = false
            if (info == null) error = R.string.photo_read_error else present(info)
        }
    }

    private fun present(info: PhotoInfo) {
        search?.cancel()
        searched = info; subject = null; detecting = true; framing = info
        search = scope.async {
            val found = try { Photos.findBird(context, info) } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "Búsqueda fallida", e); null }
            subject = found; detecting = false
            found
        }
    }

    /** Volver a encuadrar la misma foto: un mal recorte es la causa más común de un mal resultado. */
    fun adjust() { photo?.let { if (it === searched) framing = it else present(it) } }

    /** Al abandonar una foto nueva se abandona su búsqueda; la anterior la repetirá si se ajusta. */
    fun cancel() {
        if (framing !== photo) { search?.cancel(); detecting = false }
        framing = null
    }

    /** [region] null: que lo decida la búsqueda automática (o, si no encontró nada, el centro). */
    fun confirm(info: PhotoInfo, region: Box?) {
        framing = null; photo = info; result = null; error = null; usedRegion = region != null; working = true
        scope.launch {
            try {
                val found = if (region == null) search?.await() else null
                result = Photos.identify(context, info, region, found)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "Identificación fallida", e); error = R.string.photo_read_error
            } finally { working = false }
        }
    }

    private companion object { const val TAG = "BirdDroid" }
}

@Composable
fun PhotoScreen(session: PhotoSession) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(session::open) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pick.launch(arrayOf("image/*")) }
    val cameraFile = remember { File(context.cacheDir, "camara/foto.jpg").apply { parentFile?.mkdirs() } }
    val cameraUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fotos", cameraFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) session.open(cameraUri) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = gutter(), vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Title(stringResource(R.string.tab_photo)) }
            val shown = session.photo
            if (shown == null) item { EmptyPhoto() } else item { Preview(shown, session) }
            if (session.loading) item { Stack(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) } }
            if (session.working) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Green, strokeWidth = 2.dp)
                    Text(stringResource(R.string.analysing_photo), Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (val r = session.result) {
                is PhotoResult.NoBird -> item { Card { Text(stringResource(R.string.no_bird), Modifier.padding(16.dp)) } }
                is PhotoResult.Found -> {
                    item {
                        Card {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                r.candidates.forEachIndexed { i, c -> CandidateRow(c, i == 0) }
                                if (r.heardBoost) Text(stringResource(R.string.heard_recently), Modifier.padding(16.dp, 6.dp), fontSize = 12.sp, color = Green)
                            }
                        }
                    }
                    if (r.detection.needsTiebreak) item { Tiebreak(r.detection) }
                }
                null -> {}
            }
            session.error?.let { e -> item { Text(stringResource(e), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
        }
        // Fijos abajo: el resultado puede ser más alto que la pantalla y no deben perderse.
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter(), vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { camera.launch(cameraUri) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                Icon(Icons.Filled.CameraAlt, null); Text("  " + stringResource(R.string.camera))
            }
            OutlinedButton(onClick = {
                // Sin este permiso, la ubicación del EXIF llega borrada.
                if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) pick.launch(arrayOf("image/*"))
                else askMedia.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }, Modifier.weight(1f)) { Icon(Icons.Filled.PhotoLibrary, null); Text("  " + stringResource(R.string.gallery)) }
        }
    }
}

@Composable
private fun EmptyPhoto() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.CenterFocusWeak, null, Modifier.size(56.dp), tint = Green)
        Text(stringResource(R.string.photo_empty_title), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(stringResource(R.string.photo_empty_hint), fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** La foto en pequeño, para que el resultado se vea sin desplazar, y lo que miró el modelo. */
@Composable
private fun Preview(info: PhotoInfo, session: PhotoSession) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val image = remember(info) { info.bitmap.asImageBitmap() }
        Image(image, null, Modifier.heightIn(max = 200.dp).aspectRatio(info.bitmap.width.toFloat() / info.bitmap.height).clip(RoundedCornerShape(20.dp)),
            contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
        session.result?.crop?.let { crop ->
            val thumb = remember(crop) { crop.asImageBitmap() }
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = session::adjust).padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(thumb, null, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium)
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                Text(stringResource(if (session.usedRegion) R.string.selected_area else R.string.analysed_area), fontSize = 12.sp, color = color)
                Icon(Icons.Filled.Crop, null, Modifier.size(14.dp), tint = color)
                Text(stringResource(R.string.adjust), fontSize = 12.sp, color = color)
            }
        }
    }
}

// --- Encuadre ------------------------------------------------------------------------------

private const val TOP_LEFT = 0
private const val TOP_RIGHT = 1
private const val BOTTOM_LEFT = 2
private const val BOTTOM_RIGHT = 3

private sealed interface DragMode {
    data class Draw(val anchor: Offset) : DragMode
    data class Move(val original: Box, val anchor: Offset) : DragMode
    data class Resize(val corner: Int, val original: Box) : DragMode
}

private fun normalise(p: Offset, f: Rect) = Offset(((p.x - f.left) / f.width).coerceIn(0f, 1f), ((p.y - f.top) / f.height).coerceIn(0f, 1f))
private fun viewRect(b: Box, f: Rect) = Rect(f.left + b.left * f.width, f.top + b.top * f.height, f.left + b.right * f.width, f.top + b.bottom * f.height)
private fun corners(r: Rect) = listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)

/**
 * Como `PhotoFramingView` de iOS. La búsqueda automática preselecciona el pájaro; un toque lo
 * recorta por su silueta; arrastrando se dibuja un recuadro, que luego se mueve y se redimensiona
 * por las esquinas. «Foto completa» deja la decisión a la búsqueda.
 */
@Composable
fun FramingScreen(info: PhotoInfo, session: PhotoSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var selection by remember(info) { mutableStateOf<Box?>(null) }
    var highlight by remember(info) { mutableStateOf<Subject?>(null) }
    var segmenting by remember(info) { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    fun select(s: Subject) { selection = s.box; highlight = s; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    // Un pájaro encontrado se selecciona solo, salvo que ya se haya encuadrado a mano.
    LaunchedEffect(session.subject) { session.subject?.let { if (selection == null) select(it) } }

    fun tap(p: Offset) {
        if (segmenting) return
        scope.launch {
            segmenting = true
            val s = runCatching { Photos.subjectAt(context, info, p.x, p.y) }.onFailure { Log.w("BirdDroid", "Toque fallido", it) }.getOrNull()
            segmenting = false
            s?.let(::select)
        }
    }

    val onDark = Color.White
    Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Stack(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            TextButton(onClick = session::cancel, Modifier.align(Alignment.CenterStart)) { Text(stringResource(R.string.cancel), color = onDark) }
            Text(stringResource(R.string.frame_title), Modifier.align(Alignment.Center), color = onDark, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { session.confirm(info, selection) }, Modifier.align(Alignment.CenterEnd)) { Text(stringResource(R.string.identify), color = onDark, fontWeight = FontWeight.SemiBold) }
        }
        Text(stringResource(when {
            session.detecting || segmenting -> R.string.looking_subject
            selection == null -> R.string.hint_tap
            else -> R.string.hint_adjust
        }), Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(vertical = 8.dp), color = onDark.copy(alpha = 0.85f), fontSize = 12.sp, textAlign = TextAlign.Center)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val iw = info.bitmap.width.toFloat(); val ih = info.bitmap.height.toFloat()
            val cw = constraints.maxWidth.toFloat(); val ch = constraints.maxHeight.toFloat()
            val scale = min(cw / iw, ch / ih)
            val frame = Rect(Offset((cw - iw * scale) / 2, (ch - ih * scale) / 2), Size(iw * scale, ih * scale))
            val image = remember(info) { info.bitmap.asImageBitmap() }
            val mask = remember(highlight) { highlight?.mask?.asImageBitmap() }
            // Se anima al seleccionar con un toque; al arrastrar, el recuadro sigue al dedo.
            val target = selection ?: Box(0f, 0f, 1f, 1f)
            val spec = if (dragging) snap<Float>() else tween(200, easing = FastOutSlowInEasing)
            val l by animateFloatAsState(target.left, spec, label = "izquierda")
            val t by animateFloatAsState(target.top, spec, label = "arriba")
            val r by animateFloatAsState(target.right, spec, label = "derecha")
            val b by animateFloatAsState(target.bottom, spec, label = "abajo")

            Canvas(Modifier.fillMaxSize().pointerInput(frame, info) {
                val cornerRadius = 36.dp.toPx(); val tapSlop = 10.dp.toPx(); val rim = 8.dp.toPx()

                fun begin(p: Offset): DragMode {
                    selection?.let { sel ->
                        val rect = viewRect(sel, frame)
                        corners(rect).forEachIndexed { i, c -> if ((c - p).getDistance() < cornerRadius) return DragMode.Resize(i, sel) }
                        if (rect.inflate(rim).contains(p)) return DragMode.Move(sel, p)
                    }
                    return DragMode.Draw(p)
                }

                fun update(mode: DragMode, p: Offset) {
                    when (mode) {
                        is DragMode.Draw -> {
                            val a = normalise(mode.anchor, frame); val c = normalise(p, frame)
                            selection = Box(min(a.x, c.x), min(a.y, c.y), max(a.x, c.x), max(a.y, c.y)); highlight = null
                        }
                        is DragMode.Move -> {
                            val o = mode.original
                            val x = (o.left + (p.x - mode.anchor.x) / frame.width).coerceIn(0f, 1 - o.width)
                            val y = (o.top + (p.y - mode.anchor.y) / frame.height).coerceIn(0f, 1 - o.height)
                            selection = Box(x, y, x + o.width, y + o.height)
                        }
                        is DragMode.Resize -> {
                            val q = normalise(p, frame); val o = mode.original
                            var left = o.left; var top = o.top; var right = o.right; var bottom = o.bottom
                            when (mode.corner) {
                                TOP_LEFT -> { left = q.x; top = q.y }
                                TOP_RIGHT -> { right = q.x; top = q.y }
                                BOTTOM_LEFT -> { left = q.x; bottom = q.y }
                                BOTTOM_RIGHT -> { right = q.x; bottom = q.y }
                            }
                            selection = Box(min(left, right), min(top, bottom), max(left, right), max(top, bottom))
                        }
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val before = selection
                    val mode = begin(down.position)
                    var last = down.position
                    dragging = true
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position
                            if (!change.pressed) break
                            change.consume()
                            if ((last - down.position).getDistance() >= tapSlop) update(mode, last)
                        }
                    } finally { dragging = false }
                    if ((last - down.position).getDistance() < tapSlop) { selection = before; if (frame.contains(last)) tap(normalise(last, frame)) }
                    else selection?.let { if (it.width < 0.03f || it.height < 0.03f) selection = before }
                }
            }) {
                drawImage(image, dstOffset = IntOffset(frame.left.toInt(), frame.top.toInt()), dstSize = IntSize(frame.width.toInt(), frame.height.toInt()), filterQuality = FilterQuality.Medium)
                val h = highlight
                if (mask != null && h != null) {
                    val area = viewRect(h.maskArea, frame)
                    drawImage(mask, dstOffset = IntOffset(area.left.toInt(), area.top.toInt()), dstSize = IntSize(area.width.toInt(), area.height.toInt()), filterQuality = FilterQuality.Low)
                }
                val rect = viewRect(Box(l, t, r, b), frame)
                dim(frame, if (selection != null) rect else null)
                if (selection != null) {
                    drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
                    corners(rect).forEach { c -> drawCircle(Color.White, 8.dp.toPx(), c) }
                }
            }
        }

        Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { session.confirm(info, null) }, Modifier.weight(1f)) {
                Icon(Icons.Filled.AutoFixHigh, null, tint = onDark); Text("  " + stringResource(R.string.whole_photo), color = onDark)
            }
            Button(onClick = { session.confirm(info, selection) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text(stringResource(R.string.identify)) }
        }
    }
}

/** Oscurece lo que queda fuera de la selección: con un trazado par-impar, no borrando píxeles de la ventana. */
private fun DrawScope.dim(frame: Rect, hole: Rect?) {
    val path = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(frame)
        hole?.takeIf { abs(it.width) > 0f && abs(it.height) > 0f }?.let { addRect(it) }
    }
    drawPath(path, Color.Black.copy(alpha = if (hole == null) 0.15f else 0.55f))
}
