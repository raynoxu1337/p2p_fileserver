package com.raynoxu1337.p2pfileserver

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.text.format.Formatter
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensurePeerId(this)
        setContent { AppTheme { Screen(resumeTick.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

/** Generate the server identity on first run (or load it), off the main thread. */
private fun ensurePeerId(ctx: Context) {
    if (Prefs(ctx).peerId.isNotEmpty() && Identity.keyFile(ctx).exists()) return
    val app = ctx.applicationContext
    thread(name = "identity") {
        runCatching { Identity.ensure(app) }
            .onFailure { ServerState.log("app", "could not create identity: ${it.message}") }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF8AB0FF))
        else -> lightColorScheme(primary = Color(0xFF2F6FDE))
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

// --- permission helpers ------------------------------------------------------

private fun hasStorage(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun hasNotifications(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun ignoresBatteryOpt(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

private fun openAllFilesAccess(ctx: Context) {
    val pkg = Uri.parse("package:${ctx.packageName}")
    val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
    runCatching { ctx.startActivity(i) }.onFailure {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}

@android.annotation.SuppressLint("BatteryLife")
private fun askBatteryExemption(ctx: Context) {
    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    runCatching { ctx.startActivity(i) }.onFailure {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun copy(ctx: Context, label: String, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

/** Roots of internal storage and SD cards, plus a few common folders. */
private fun folderPresets(ctx: Context): List<Pair<String, String>> {
    val internal = Environment.getExternalStorageDirectory().path
    val out = mutableListOf("Whole phone" to internal)
    ctx.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, f ->
        val root = f.path.substringBefore("/Android/")
        out += (if (i == 0) "SD card" else "SD card ${i + 1}") to root
    }
    listOf("DCIM", "Download", "Documents", "Movies", "Music").forEach { out += it to "$internal/$it" }
    return out
}

// --- UI ------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Screen(resumeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val status by ServerState.status.collectAsStateWithLifecycle()
    val log by ServerState.log.collectAsStateWithLifecycle()
    var edits by remember { mutableIntStateOf(0) } // bump to re-read prefs

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { edits++ }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { edits++ }

    LaunchedEffect(Unit) {
        if (status.peerId.isEmpty() && prefs.peerId.isNotEmpty()) ServerState.update { it.copy(peerId = prefs.peerId) }
    }

    // Settings live in SharedPreferences (not Compose state): reading these
    // counters makes every part below refresh after an edit or onResume.
    check(resumeTick >= 0 && edits >= 0)
    val running = status.phase == Phase.RUNNING || status.phase == Phase.STARTING
    val peerId = status.peerId.ifEmpty { prefs.peerId }
    val link = prefs.shareLink(peerId)
    val needsRestart = running && status.runningConfig.isNotEmpty() &&
        status.runningConfig != ServerService.configFingerprint(prefs)

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                Text("P2P File Server", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Turns this phone into a file server you can open from any browser. No domain, no port forwarding.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Status + start/stop
        item {
            Section {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (color, label) = statusLine(status)
                    Box(Modifier.size(12.dp).background(color, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
                if (running && status.phase == Phase.RUNNING) {
                    Text(
                        "${status.relayAddrs} relay route(s) · ${status.peers} p2p peers " +
                            "(↑ ${size(ctx, status.traffic.fileTx + status.traffic.netTx)} " +
                            "↓ ${size(ctx, status.traffic.fileRx + status.traffic.netRx)}) · ${status.sessions} visitor(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                status.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        OutlinedButton(onClick = { ServerService.stop(ctx) }) { Text("Stop server") }
                        if (needsRestart) Button(onClick = { ServerService.restart(ctx) }) { Text("Apply changes") }
                    } else {
                        Button(
                            enabled = status.phase != Phase.STOPPING,
                            onClick = {
                                if (!hasNotifications(ctx) && Build.VERSION.SDK_INT >= 33) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                ServerService.start(ctx)
                            },
                        ) { Text("Start server") }
                    }
                }
            }
        }

        // Setup checklist
        val todo = buildList {
            if (!hasStorage(ctx)) add("storage")
            if (!ignoresBatteryOpt(ctx)) add("battery")
            if (!hasNotifications(ctx)) add("notif")
        }
        if (todo.isNotEmpty()) item {
            Section("Setup") {
                todo.forEach { t ->
                    when (t) {
                        "storage" -> Todo("Allow access to files so they can be shared.", "Allow") {
                            if (Build.VERSION.SDK_INT >= 30) openAllFilesAccess(ctx)
                            else storageLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                        }
                        "battery" -> Todo("Let the server run in the background without battery restrictions.", "Allow") { askBatteryExemption(ctx) }
                        "notif" -> Todo("Allow the status notification.", "Allow") {
                            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            }
        }

        // Sharing
        item {
            Section("Open from anywhere") {
                check(edits >= 0)
                if (peerId.isEmpty()) {
                    Text("Creating invite token…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Hint("Scan the QR code or open the link in any browser. Then log in with the details below.")
                    val qr = remember(link) { QrCode.bitmap(link).asImageBitmap() }
                    Image(qr, "QR code of the link", Modifier.size(200.dp).align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { Mono(link) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        FilledTonalButton(onClick = { copy(ctx, "link", link) }) { Text("Copy link") }
                        FilledTonalButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                            ctx.startActivity(Intent.createChooser(send, "Share server link"))
                        }) { Text("Share") }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Label("Invite token")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Mono(peerId) }
                        TextButton(onClick = { copy(ctx, "invite token", peerId) }) { Text("Copy") }
                    }
                    Hint("Already part of the link. Share it on its own when someone opens the resolver page directly.")
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Label("Login")
                    var show by rememberSaveable { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Mono("${prefs.adminUser} / ${if (show) prefs.adminPassword else "••••••••"}", Modifier.weight(1f))
                        TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
                        TextButton(onClick = { copy(ctx, "password", prefs.adminPassword) }) { Text("Copy") }
                    }
                }
            }
        }

        // Usage: storage and bandwidth, so heavy traffic or a slow uplink is visible
        item {
            Section("Usage") {
                check(edits >= 0)
                StorageGauge(prefs.sharedFolder)
                if (running) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    TrafficTable(status.traffic)
                }
            }
        }

        // Settings
        item {
            Section("Settings") {
                check(edits >= 0)
                Field("Shared folder", prefs.sharedFolder) { prefs.sharedFolder = it; edits++ }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    folderPresets(ctx).forEach { (name, path) ->
                        AssistChip(onClick = { prefs.sharedFolder = path; edits++ }, label = { Text(name) })
                    }
                }
                Field("Server name", prefs.serverName) { prefs.serverName = it; edits++ }
                Field("Admin user", prefs.adminUser) { prefs.adminUser = it; edits++ }
                Field("Admin password", prefs.adminPassword, secret = true) { prefs.adminPassword = it; edits++ }
                TextButton(onClick = { prefs.adminPassword = Prefs.newPassword(); edits++ }) { Text("Generate new password") }
                Toggle("Read-only (no uploads, edits or deletes)", prefs.readOnly) { prefs.readOnly = it; edits++ }
                Toggle("Start when the phone boots", prefs.startOnBoot) { prefs.startOnBoot = it; edits++ }
            }
        }

        item {
            var open by rememberSaveable { mutableStateOf(false) }
            Section("Advanced", onTitleClick = { open = !open }, collapsed = !open) {
                check(edits >= 0)
                if (open) {
                    Field("Custom resolver page URL", prefs.resolverUrl, Prefs.DEFAULT_RESOLVER) {
                        prefs.resolverUrl = it; edits++
                    }
                    if (prefs.resolverOverrideInvalid) {
                        Text("Not a valid address, so the default resolver is used.", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    Hint(
                        "Leave empty to use the public resolver page. Only set this if you host your own copy of the " +
                            "p2p_resolver folder. Files are then served under ${prefs.webBasePath}, so the page must be " +
                            "hosted at that folder. Changing it changes the link."
                    )
                    HorizontalDivider(Modifier.padding(bottom = 12.dp))
                    Field("Max p2p peers", prefs.maxPeers.toString(), keyboard = KeyboardType.Number) {
                        it.toIntOrNull()?.takeIf { n -> n in Prefs.MAX_PEERS_RANGE }?.let { n -> prefs.maxPeers = n; edits++ }
                    }
                    Hint(
                        "Soft limit (${Prefs.MAX_PEERS_RANGE.first}–${Prefs.MAX_PEERS_RANGE.last}, default ${Prefs.DEFAULT_MAX_PEERS}). " +
                            "Above it the server drops back to about a third. Lower it on slow or metered internet. " +
                            "Too low makes the server slower to find."
                    )
                    Toggle("Peer-only mode", prefs.peerOnly) { prefs.peerOnly = it; edits++ }
                    Hint(
                        if (prefs.peerOnly) "Uses the p2p network as a client only and doesn't answer other peers' lookups. " +
                            "Recommended for phones."
                        else "Also answers other peers' lookups (DHT server). Uses noticeably more data and only helps " +
                            "if this phone is reachable from the internet (public IPv6 or a forwarded port)."
                    )
                    HorizontalDivider(Modifier.padding(bottom = 12.dp))
                    Field("P2P port (TCP+UDP)", prefs.p2pPort.toString(), keyboard = KeyboardType.Number) {
                        it.toIntOrNull()?.takeIf { p -> p in 1024..65535 }?.let { p -> prefs.p2pPort = p; edits++ }
                    }
                    Hint("If visitors on strict mobile networks cannot connect, add a TURN relay (e.g. turn:host:3478).")
                    Field("TURN server", prefs.turnUrl, "turn:turn.example.com:3478") { prefs.turnUrl = it; edits++ }
                    Field("TURN username", prefs.turnUser) { prefs.turnUser = it; edits++ }
                    Field("TURN password", prefs.turnPass, secret = true) { prefs.turnPass = it; edits++ }

                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    IdentityTools(running = running, currentId = peerId) { edits++ }
                }
            }
        }

        item {
            var open by rememberSaveable { mutableStateOf(false) }
            Section("Log", onTitleClick = { open = !open }, collapsed = !open) {
                if (open) {
                    SelectionContainer {
                        Text(
                            log.takeLast(150).joinToString("\n").ifEmpty { "(empty)" },
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        )
                    }
                    TextButton(onClick = { copy(ctx, "log", log.joinToString("\n")) }) { Text("Copy log") }
                }
            }
        }
    }
}

@Composable
private fun statusLine(s: Status): Pair<Color, String> = when {
    s.phase == Phase.STOPPED -> Color(0xFF9AA0A6) to "Stopped"
    s.phase == Phase.STOPPING -> Color(0xFF9AA0A6) to "Stopping…"
    s.phase == Phase.STARTING -> Color(0xFFE3A008) to "Starting…"
    !s.backendUp -> Color(0xFFE3A008) to "Starting file server…"
    !s.online -> Color(0xFFE3A008) to "Connecting to the p2p network…"
    else -> Color(0xFF1F9D55) to "Online"
}

@Composable
private fun Section(
    title: String? = null,
    onTitleClick: (() -> Unit)? = null,
    collapsed: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(
                    Modifier.fillMaxWidth().let { if (onTitleClick != null) it.clickable(onClick = onTitleClick) else it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (onTitleClick != null) Text(if (collapsed) "Show" else "Hide", color = MaterialTheme.colorScheme.primary)
                }
                if (!collapsed) Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
private fun Todo(text: String, action: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("•  $text", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (action != null) TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
internal fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp))

@Composable
private fun Mono(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String? = null,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) ({ TextButton(onClick = { reveal = !reveal }) { Text(if (reveal) "Hide" else "Show") } }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

private fun size(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

private fun rate(ctx: Context, bytesPerSec: Long): String = size(ctx, bytesPerSec) + "/s"

/** Free space on the volume holding the shared folder, refreshed every few seconds. */
@Composable
private fun StorageGauge(folder: String) {
    val ctx = LocalContext.current
    var space by remember(folder) { mutableStateOf<Pair<Long, Long>?>(null) } // free, total
    LaunchedEffect(folder) {
        while (true) {
            space = runCatching { StatFs(folder).let { it.availableBytes to it.totalBytes } }.getOrNull()
            delay(10_000)
        }
    }
    Label("Free storage")
    val (free, total) = space ?: run {
        Text("Can't read the shared folder", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val used = if (total > 0) (total - free).toFloat() / total else 0f
    val low = total > 0 && free < total / 10
    LinearProgressIndicator(
        progress = { used },
        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(8.dp),
        drawStopIndicator = {},
    )
    Text(
        "${size(ctx, free)} free of ${size(ctx, total)}" + if (low) " · almost full" else "",
        style = MaterialTheme.typography.bodySmall,
        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** File-server vs. p2p upkeep traffic: current rate and total since the server started. */
@Composable
private fun TrafficTable(t: Traffic) {
    val ctx = LocalContext.current
    @Composable
    fun Cell(up: String, down: String, modifier: Modifier) = Column(modifier) {
        Mono("↑ $up")
        Mono("↓ $down")
    }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1.1f))
        Box(Modifier.weight(1f)) { Label("Now") }
        Box(Modifier.weight(1f)) { Label("Since start") }
    }
    listOf(
        "File server" to listOf(t.fileTxRate, t.fileRxRate, t.fileTx, t.fileRx),
        "Publishing" to listOf(t.netTxRate, t.netRxRate, t.netTx, t.netRx),
    ).forEach { (name, v) ->
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1.1f), style = MaterialTheme.typography.bodyMedium)
            Cell(rate(ctx, v[0]), rate(ctx, v[1]), Modifier.weight(1f))
            Cell(size(ctx, v[2]), size(ctx, v[3]), Modifier.weight(1f))
        }
    }
    Text(
        "↑ sent by this phone, ↓ received. File server is what visitors browse, upload and download. " +
            "Publishing is the upkeep that keeps the server findable on the p2p network.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp),
    )
}
