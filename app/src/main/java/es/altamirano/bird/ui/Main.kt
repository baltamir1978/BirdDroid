@file:OptIn(ExperimentalMaterial3Api::class)

package es.altamirano.bird.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import kotlin.math.sqrt
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import es.altamirano.bird.PhotoInfo
import es.altamirano.bird.PhotoResult
import es.altamirano.bird.Photos
import es.altamirano.bird.R
import es.altamirano.bird.WikiInfo
import es.altamirano.bird.Wikipedia
import es.altamirano.bird.XenoCanto
import es.altamirano.bird.app
import es.altamirano.bird.core.Candidate
import es.altamirano.bird.data.Alternative
import es.altamirano.bird.data.Detection
import es.altamirano.bird.core.Signal
import es.altamirano.bird.listen.ListenBus
import es.altamirano.bird.listen.ListenService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** El verde de la app (el mismo de BirdApp, RadioApp y Tiempo ES). */
val Green = Color(0xFF239E7B)
val GreenDeep = Color(0xFF0B6F53)

@Composable
fun BirdTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF2BBE92), onPrimary = Color.Black, primaryContainer = Color(0xFF14332A),
        background = Color.Black, surface = Color.Black, surfaceContainer = Color(0xFF1C1C1E))
    else lightColorScheme(primary = Color(0xFF1A7F60), onPrimary = Color.White, primaryContainer = Color(0xFFE6F5ED),
        background = Color(0xFFF2F2F7), surface = Color(0xFFF2F2F7), surfaceContainer = Color.White),
    content = content,
)

class MainActivity : ComponentActivity() {
    /** Una foto que llega de otra app (compartir): abre la pestaña Foto con ella. */
    private val incoming = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { BirdTheme { Root(incoming) } }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    override fun onStart() { super.onStart(); ListenBus.foreground = true }
    override fun onStop() { ListenBus.foreground = false; super.onStop() }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            incoming.value = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

@Composable
private fun Root(incoming: MutableStateFlow<Uri?>) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val shared by incoming.collectAsStateWithLifecycle()
    LaunchedEffect(shared) { if (shared != null) tab = 1 }
    var detail by remember { mutableStateOf<Detection?>(null) }
    detail?.let { d -> BackHandler { detail = null }; DetailScreen(d) { detail = null }; return }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            listOf(Triple(R.string.tab_listen, Icons.Filled.Mic, 0), Triple(R.string.tab_photo, Icons.Filled.CameraAlt, 1),
                Triple(R.string.tab_history, Icons.Filled.History, 2), Triple(R.string.tab_settings, Icons.Filled.Settings, 3)).forEach { (l, ic, i) ->
                NavigationBarItem(tab == i, { tab = i }, { Icon(ic, null) }, label = { Text(stringResource(l)) },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer))
            }
        }
    }) { p ->
        Box(Modifier.padding(bottom = p.calculateBottomPadding()).fillMaxSize()) {
            when (tab) {
                0 -> ListenScreen { detail = it }
                1 -> PhotoScreen(shared) { incoming.value = null }
                2 -> HistoryScreen { detail = it }
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp, bottom = 8.dp))

@Composable
private fun Card(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) { content() }

private fun pct(c: Double) = "${(c * 100).roundToInt()} %"

// --- Escuchar ------------------------------------------------------------------------------

@Composable
private fun ListenScreen(onOpen: (Detection) -> Unit) {
    val context = LocalContext.current
    val state by ListenBus.state.collectAsStateWithLifecycle()
    val history by context.app.history.items.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) ListenService.start(context)
    }
    val latest = history.firstOrNull { !it.photo }
    val tiebreak = latest != null && latest.needsTiebreak && System.currentTimeMillis() - latest.at < 600_000
    // Como en iOS: el pájaro y lo que se oye en el centro, el botón del micrófono abajo
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Title(stringResource(R.string.tab_listen))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
            item { BirdWave(state.level, state.listening, Modifier.fillMaxWidth().height(110.dp)) }
            item {
                Text(when {
                    state.error != null -> state.error!!
                    !state.listening -> stringResource(R.string.tap_to_listen)
                    state.analyzing -> stringResource(R.string.analyzing)
                    state.thinking != null -> stringResource(R.string.maybe, state.thinking!!.name, pct(state.thinking!!.confidence))
                    else -> stringResource(R.string.listening)
                }, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.candidates.isNotEmpty()) item {
                Card {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        state.candidates.forEachIndexed { i, c -> CandidateRow(c, i == 0) }
                    }
                }
            }
            if (tiebreak) item { Tiebreak(latest!!) }
            if (history.isNotEmpty()) item { Text(stringResource(R.string.recent), fontWeight = FontWeight.SemiBold) }
            items(history.take(5), key = { it.id }) { d -> HistoryRow(d) { onOpen(d) } }
        }
        val color by animateColorAsState(if (state.listening) Color(0xFFFF3B30) else Green, label = "botón")
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(80.dp).shadow(if (state.listening) 16.dp else 6.dp, CircleShape, spotColor = color, ambientColor = color)
                .clip(CircleShape).background(color).clickable {
                    if (state.listening) ListenService.stop(context)
                    else {
                        val perms = buildList { add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ListenService.start(context)
                        else ask.launch(perms.toTypedArray())
                    }
                }, contentAlignment = Alignment.Center) {
                Icon(if (state.listening) Icons.Filled.Stop else Icons.Filled.Mic, null, Modifier.size(36.dp), tint = Color.White)
            }
        }
    }
}

/** Silueta de pájaro de perfil (mirando a la derecha), en una caja de 100×80. */
private val BirdShape: Path by lazy {
    PathParser().parsePathString(
        "M70 13C79 13 85 18 87 25L98 28L87 32C86 44 79 54 67 60C57 65 46 67 36 66L24 73L5 77L17 66L6 61L28 57" +
        "C33 44 45 37 55 33C55 21 61 13 70 13Z" +
        "M76 21A3 3 0 1 0 76.01 21Z" +                    // el ojo (hueco)
        "M52 64L50 76L53 76L55 64ZM60 62L60 75L63 75L63 61Z"  // las patas
    ).toPath().apply { fillType = PathFillType.EvenOdd }
}

/**
 * Lo mismo que `BirdWaveView` de iOS: el pájaro se llena de verde desde abajo según el volumen,
 * crece un poco con el canto y, si hay señal, suelta ondas por detrás.
 */
@Composable
private fun BirdWave(level: Float, listening: Boolean, modifier: Modifier = Modifier) {
    // En decibelios, de −70 dBFS (silencio de campo con el micrófono sin procesar) a −20 (muy fuerte):
    // sin control de ganancia, el canto de un pájaro a unos metros queda por −50 … −35 dBFS, y con
    // la escala lineal casi no se veía. Es sólo para verlo: BirdNET no depende del volumen.
    val target = if (listening) (Signal.dbfs(level) + 70f) / 50f else 0f
    val amp by animateFloatAsState(target.coerceIn(0f, 1f), spring(dampingRatio = 0.7f, stiffness = 400f), label = "volumen")
    val fill = if (listening) 0.18f + amp * 0.82f else 0.12f
    val waves = rememberInfiniteTransition(label = "ondas")
    val t by waves.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "t")
    Canvas(modifier) {
        val h = size.height
        val c = center
        if (amp > 0.05f) for (ring in 0..2) {
            val p = (t + ring / 3f) % 1f
            drawCircle(Green.copy(alpha = (1 - p) * amp * 0.5f), h * (0.5f + p * 1.6f) / 2, c, style = Stroke(2.dp.toPx()))
        }
        val w = h * 1.3f
        val s = (1 + amp * 0.12f) * minOf(w / 100f, h / 80f)
        withTransform({
            translate(c.x - 50f * s, c.y - 40f * s)
            scale(s, s, Offset.Zero)
        }) {
            drawPath(BirdShape, Green.copy(alpha = 0.18f))
            clipRect(top = 80f * (1 - fill)) {
                drawPath(BirdShape, Brush.verticalGradient(listOf(Green.copy(alpha = 0.65f), Green), startY = 80f * (1 - fill), endY = 80f))
            }
        }
    }
}

@Composable
private fun CandidateRow(c: Candidate, top: Boolean) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.name, fontWeight = if (top) FontWeight.Bold else null, fontSize = if (top) 18.sp else 15.sp)
            Text(c.scientific, fontStyle = FontStyle.Italic, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(pct(c.confidence), fontWeight = FontWeight.SemiBold, color = if (top) Green else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** «Estas especies se parecen: ¿cuál era?» Sólo cuando el segundo queda cerca del primero. */
@Composable
private fun Tiebreak(d: Detection) {
    val app = LocalContext.current.app
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.look_alike), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (listOf(Alternative(d.scientific, d.name, d.confidence, d.imageUrl)) + d.alternatives).forEach { a ->
                val current = a.scientific == d.scientific
                Row(Modifier.fillMaxWidth().clickable(enabled = !current) { app.history.replace(d.choose(a)) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Thumb(a.scientific, 40)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(a.name, fontWeight = if (current) FontWeight.SemiBold else null)
                        Text(a.scientific, fontStyle = FontStyle.Italic, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(pct(a.confidence), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (current) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (current) Green else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Thumb(scientific: String, size: Int) {
    var info by remember(scientific) { mutableStateOf<WikiInfo?>(null) }
    LaunchedEffect(scientific) { info = Wikipedia.summary(scientific) }
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer)) {
        info?.image?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

// --- Foto ----------------------------------------------------------------------------------

@Composable
private fun PhotoScreen(shared: Uri?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<PhotoInfo?>(null) }
    var region by remember { mutableStateOf<RectF?>(null) }
    var result by remember { mutableStateOf<PhotoResult?>(null) }
    var working by remember { mutableStateOf(false) }
    fun open(uri: Uri) = scope.launch { working = true; result = null; region = null; info = Photos.load(context, uri); working = false }
    LaunchedEffect(shared) { shared?.let { open(it); onConsumed() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::open) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pick.launch(arrayOf("image/*")) }
    val cameraFile = remember { File(context.cacheDir, "camara/foto.jpg").apply { parentFile?.mkdirs() } }
    val cameraUri = remember { FileProvider.getUriForFile(context, "${context.packageName}.fotos", cameraFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) open(cameraUri) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Title(stringResource(R.string.tab_photo)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    // Sin este permiso, la ubicación del EXIF llega borrada.
                    if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) pick.launch(arrayOf("image/*"))
                    else askMedia.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Icon(Icons.Filled.PhotoLibrary, null); Text("  " + stringResource(R.string.gallery)) }
                OutlinedButton(onClick = { camera.launch(cameraUri) }) { Icon(Icons.Filled.CameraAlt, null); Text("  " + stringResource(R.string.camera)) }
            }
        }
        if (working) item { CircularProgressIndicator(color = Green) }
        info?.let { inf ->
            item {
                Text(stringResource(R.string.framing_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Framing(inf, region) { region = it }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { scope.launch { working = true; result = Photos.identify(context, inf, region, null); working = false } },
                        colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text(stringResource(R.string.identify)) }
                    if (region != null) TextButton(onClick = { region = null }) { Text(stringResource(R.string.auto_crop)) }
                }
            }
        }
        when (val r = result) {
            is PhotoResult.NoBird -> item { Card { Text(stringResource(R.string.no_bird), Modifier.padding(16.dp)) } }
            is PhotoResult.Found -> {
                item {
                    Card {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Image(r.crop.asImageBitmap(), null, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                                Text(stringResource(R.string.analysed), Modifier.padding(start = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            r.candidates.forEachIndexed { i, c -> CandidateRow(c, i == 0) }
                            if (r.heardBoost) Text(stringResource(R.string.heard_recently), Modifier.padding(16.dp, 6.dp), fontSize = 12.sp, color = Green)
                        }
                    }
                }
                if (r.detection.needsTiebreak) item { Tiebreak(r.detection) }
            }
            null -> {}
        }
    }
}

/** Encuadre a mano: se arrastra un recuadro sobre el pájaro. */
@Composable
private fun Framing(info: PhotoInfo, region: RectF?, onRegion: (RectF?) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    val ratio = info.bitmap.width.toFloat() / info.bitmap.height
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).onSizeChanged { size = it }
        .pointerInput(info) {
            detectDragGestures(onDragStart = { start = it }, onDragEnd = { start = null }) { change, _ ->
                val s = start ?: return@detectDragGestures
                val e = change.position
                val r = RectF(minOf(s.x, e.x) / size.width, minOf(s.y, e.y) / size.height, maxOf(s.x, e.x) / size.width, maxOf(s.y, e.y) / size.height)
                if (r.width() > 0.05f && r.height() > 0.05f) onRegion(RectF(r.left.coerceIn(0f, 1f), r.top.coerceIn(0f, 1f), r.right.coerceIn(0f, 1f), r.bottom.coerceIn(0f, 1f)))
            }
        }) {
        Image(info.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        region?.let { r ->
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color(0x66000000))
                drawRect(Color.Transparent, Offset(r.left * size.width, r.top * size.height), Size(r.width() * size.width, r.height() * size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                drawRect(Color.White, Offset(r.left * size.width, r.top * size.height), Size(r.width() * size.width, r.height() * size.height), style = Stroke(3f))
            }
        }
    }
}

// --- Historial -----------------------------------------------------------------------------

@Composable
private fun HistoryScreen(onOpen: (Detection) -> Unit) {
    val app = LocalContext.current.app
    val items by app.history.items.collectAsStateWithLifecycle()
    val byDay = items.groupBy { Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalDate() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Title(stringResource(R.string.tab_history)) }
        if (items.isEmpty()) item { Text(stringResource(R.string.history_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        byDay.forEach { (day, list) ->
            item { Text(if (day == LocalDate.now()) stringResource(R.string.today) else day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
            items(list, key = { it.id }) { d -> HistoryRow(d) { onOpen(d) } }
        }
    }
}

@Composable
private fun HistoryRow(d: Detection, onClick: () -> Unit) {
    Card {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(d.scientific, 48)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(d.name, fontWeight = FontWeight.SemiBold)
                Text(d.scientific, fontStyle = FontStyle.Italic, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Icon(if (d.photo) Icons.Filled.CameraAlt else Icons.Filled.Mic, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Instant.ofEpochMilli(d.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 12.sp)
                Text(pct(d.confidence), fontSize = 12.sp, color = Green)
            }
        }
    }
}

/** La ficha: foto y texto de Wikipedia, el canto de xeno-canto y, si hubo dudas, el desempate. */
@Composable
private fun DetailScreen(start: Detection, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val items by app.history.items.collectAsStateWithLifecycle()
    val d = items.firstOrNull { it.id == start.id } ?: start
    var info by remember(d.scientific) { mutableStateOf<WikiInfo?>(null) }
    LaunchedEffect(d.scientific) { info = Wikipedia.summary(d.scientific) }
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var songMsg by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val noKey = stringResource(R.string.xc_no_key); val noSong = stringResource(R.string.xc_none)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text(d.name) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = { IconButton(onClick = { app.history.remove(d); onBack() }) { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { p ->
        LazyColumn(Modifier.padding(p).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { info?.image?.let { AsyncImage(it, null, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop) } }
            item {
                Text(d.scientific, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${pct(d.confidence)} · " + Instant.ofEpochMilli(d.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedButton(onClick = {
                    if (player != null) { player?.release(); player = null; return@OutlinedButton }
                    scope.launch {
                        val key = app.prefs.xenoCantoKey
                        if (key.isBlank()) { songMsg = noKey; return@launch }
                        val url = XenoCanto.recording(d.scientific, key) ?: run { songMsg = noSong; return@launch }
                        player = MediaPlayer().apply { setDataSource(url); setOnPreparedListener { it.start() }; prepareAsync() }
                    }
                }) { Icon(if (player != null) Icons.Filled.Stop else Icons.Filled.PlayArrow, null); Text("  " + stringResource(R.string.play_song)) }
                songMsg?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (d.needsTiebreak) item { Tiebreak(d) }
            info?.extract?.let { item { Text(it) } }
            item { Text("Wikipedia (CC BY-SA)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// --- Ajustes -------------------------------------------------------------------------------

@Composable
private fun SettingsScreen() {
    val app = LocalContext.current.app
    val s by app.prefs.settings.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(app.prefs.xenoCantoKey) }
    var bg by remember { mutableIntStateOf(app.prefs.backgroundMinutes) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title(stringResource(R.string.tab_settings)) }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    SliderRow(stringResource(R.string.threshold), s.threshold, 0.1f..0.9f, pct(s.threshold.toDouble())) { v -> app.prefs.update { it.copy(threshold = v) } }
                    SliderRow(stringResource(R.string.sensitivity), s.sensitivity, 0.5f..1.5f, "%.2f".format(s.sensitivity)) { v -> app.prefs.update { it.copy(sensitivity = v) } }
                    SliderRow(stringResource(R.string.location_filter), s.locationInfluence, 0f..1f, pct(s.locationInfluence.toDouble())) { v -> app.prefs.update { it.copy(locationInfluence = v) } }
                    Text(stringResource(R.string.location_filter_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(vertical = 4.dp)) {
                    SwitchRow(stringResource(R.string.smoothing), s.smoothing) { v -> app.prefs.update { it.copy(smoothing = v) } }
                    HorizontalDivider()
                    SwitchRow(stringResource(R.string.high_pass), s.highPass) { v -> app.prefs.update { it.copy(highPass = v) } }
                    HorizontalDivider()
                    SwitchRow(stringResource(R.string.signal_gate), s.signalGate) { v -> app.prefs.update { it.copy(signalGate = v) } }
                    HorizontalDivider()
                    SwitchRow(stringResource(R.string.clip_gate), s.clipGate) { v -> app.prefs.update { it.copy(clipGate = v) } }
                    HorizontalDivider()
                    SwitchRow(stringResource(R.string.fusion), s.fusion) { v -> app.prefs.update { it.copy(fusion = v) } }
                }
            }
        }
        item {
            val context = LocalContext.current
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { shareRecentAudio(context) }) { Text(stringResource(R.string.share_audio)) }
                    Text(stringResource(R.string.share_audio_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    SliderRow(stringResource(R.string.background_minutes), bg.toFloat(), 5f..120f, "$bg min") { v -> bg = v.roundToInt(); app.prefs.backgroundMinutes = bg }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("xeno-canto", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.xc_key)) },
                        visualTransformation = PasswordVisualTransformation())
                    TextButton(enabled = key != app.prefs.xenoCantoKey, onClick = { app.prefs.xenoCantoKey = key }) { Text(stringResource(R.string.save)) }
                }
            }
        }
        item {
            Text(stringResource(R.string.credits), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Los últimos 15 s del micrófono en WAV, tal cual le llegan al modelo. */
private fun shareRecentAudio(context: Context) {
    val audio = ListenBus.recentAudio()
    if (audio.isEmpty()) { Toast.makeText(context, R.string.share_audio_empty, Toast.LENGTH_SHORT).show(); return }
    val dir = java.io.File(context.cacheDir, "audio").apply { mkdirs() }
    val f = java.io.File(dir, "birddroid-${System.currentTimeMillis() / 1000}.wav")
    dir.listFiles()?.forEach { it.delete() }
    f.writeBytes(Signal.wav(audio))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fotos", f)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/wav")
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    Row { Text(label, Modifier.weight(1f)); Text(shown, fontWeight = FontWeight.SemiBold) }
    Slider(value, onChange, valueRange = range)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }
}
