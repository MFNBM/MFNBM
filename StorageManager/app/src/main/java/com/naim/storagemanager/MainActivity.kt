package com.naim.storagemanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { Root() }
        }
    }
}

private fun fmt(ctx: Context, bytes: Long): String = Formatter.formatFileSize(ctx, bytes)

@Composable
fun Root() {
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Overview", "Apps", "Cleaner")
    Scaffold { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "Storage Manager",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> OverviewTab()
                1 -> AppsTab()
                else -> CleanerTab()
            }
        }
    }
}

@Composable
fun PermissionCard() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(Scanner.hasAllFilesAccess(ctx)) }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = Scanner.hasAllFilesAccess(ctx)
    }
    // Re-check when the user returns from Settings.
    LaunchedEffect(Unit) { granted = Scanner.hasAllFilesAccess(ctx) }
    if (granted) return
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("All-files access needed", style = MaterialTheme.typography.titleMedium)
            Text("To find APKs, junk and duplicates anywhere on your phone, allow file access.")
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${ctx.packageName}")
                            )
                        )
                    } else {
                        legacy.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }) { Text("Grant access") }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { granted = Scanner.hasAllFilesAccess(ctx) }) { Text("I granted it") }
            }
        }
    }
}

@Composable
fun OverviewTab() {
    val ctx = LocalContext.current
    val stat = StatFs(Environment.getDataDirectory().path)
    val total = stat.totalBytes
    val free = stat.availableBytes
    val used = total - free
    Column(Modifier.fillMaxSize()) {
        PermissionCard()
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Phone storage", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { used.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Used ${fmt(ctx, used)} of ${fmt(ctx, total)}")
                Text("Free ${fmt(ctx, free)}")
            }
        }
        Text(
            "Use the Apps tab to review and uninstall apps, and the Cleaner tab for a deep scan of APKs, junk, duplicates, large files and empty folders.",
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun AppsTab() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var includeSystem by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var usageOk by remember { mutableStateOf(Apps.hasUsageAccess(ctx)) }
    val selected = remember { mutableStateListOf<String>() }
    val queue = remember { mutableStateListOf<String>() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (queue.isNotEmpty()) queue.removeAt(0)
        if (queue.isEmpty()) refresh++
    }
    LaunchedEffect(queue.firstOrNull()) {
        val pkg = queue.firstOrNull() ?: return@LaunchedEffect
        launcher.launch(
            Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        )
    }
    LaunchedEffect(includeSystem, refresh) {
        loading = true
        usageOk = Apps.hasUsageAccess(ctx)
        apps = withContext(Dispatchers.IO) { Apps.load(ctx, includeSystem) }
        selected.retainAll(apps.map { it.pkg }.toSet())
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("System apps", Modifier.weight(1f))
            Switch(checked = includeSystem, onCheckedChange = { includeSystem = it })
        }
        if (!usageOk) {
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) { Text("Grant usage access for exact app + cache sizes") }
        }
        Row(Modifier.padding(horizontal = 16.dp)) {
            Button(
                enabled = selected.isNotEmpty(),
                onClick = { queue.clear(); queue.addAll(selected.toList()); selected.clear() }
            ) { Text("Uninstall selected (${selected.size})") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.pkg }) { app ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = app.pkg in selected,
                        onCheckedChange = { c -> if (c) selected.add(app.pkg) else selected.remove(app.pkg) }
                    )
                    Column(Modifier.weight(1f).clickable { menu = true }) {
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${app.pkg} ${app.version}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("App info (clear cache / data)") }, onClick = {
                                menu = false
                                ctx.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${app.pkg}")
                                    )
                                )
                            })
                            DropdownMenuItem(text = { Text("Uninstall") }, onClick = {
                                menu = false
                                queue.clear(); queue.add(app.pkg)
                            })
                        }
                    }
                    Text(
                        (if (app.exact) "" else "~") + fmt(ctx, app.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun CleanerTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var largeMb by remember { mutableFloatStateOf(100f) }
    var confirm by remember { mutableStateOf(false) }
    var freedMsg by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    val selectedItems = items.filter { it.path in selected }
    val selectedBytes = selectedItems.sumOf { it.size }

    Column(Modifier.fillMaxSize()) {
        PermissionCard()
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Large file threshold: ${largeMb.toInt()} MB")
            Slider(value = largeMb, onValueChange = { largeMb = it }, valueRange = 20f..1000f)
            Row {
                Button(enabled = !scanning, onClick = {
                    scanning = true; freedMsg = ""; selected.clear(); items = emptyList()
                    scope.launch(Dispatchers.IO) {
                        val result = Scanner.scan(ctx, largeMb.toInt()) { progress = it }
                        withContext(Dispatchers.Main) { items = result; scanning = false }
                    }
                }) { Text(if (scanning) "Scanning…" else "Deep scan") }
                Spacer(Modifier.padding(4.dp))
                Button(enabled = selected.isNotEmpty(), onClick = { confirm = true }) {
                    Text("Delete (${selected.size} · ${fmt(ctx, selectedBytes)})")
                }
            }
            if (scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Text(progress, style = MaterialTheme.typography.bodySmall)
            }
            if (freedMsg.isNotEmpty()) Text(freedMsg, color = MaterialTheme.colorScheme.primary)
        }

        val grouped = items.groupBy { it.category }
        LazyColumn(Modifier.weight(1f)) {
            Category.values().forEach { cat ->
                val list = grouped[cat] ?: return@forEach
                item(key = "h_$cat") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${cat.label} (${list.size} · ${fmt(ctx, list.sumOf { it.size })})",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val paths = list.map { it.path }
                            if (paths.all { it in selected }) selected.removeAll(paths.toSet())
                            else paths.forEach { if (it !in selected) selected.add(it) }
                        }) { Text("Toggle all") }
                    }
                }
                items(list, key = { it.path }) { it ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = it.path in selected,
                            onCheckedChange = { c -> if (c) selected.add(it.path) else selected.remove(it.path) }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(File(it.path).name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                it.detail, style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                it.path.removePrefix(Environment.getExternalStorageDirectory().absolutePath),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(fmt(ctx, it.size))
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${selected.size} items?") },
            text = { Text("This permanently deletes ${fmt(ctx, selectedBytes)}. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val toDelete = selectedItems
                    scope.launch(Dispatchers.IO) {
                        var freed = 0L
                        val deleted = mutableSetOf<String>()
                        for (i in toDelete) {
                            val f = File(i.path)
                            if (f.exists() && f.delete()) { freed += i.size; deleted += i.path }
                        }
                        withContext(Dispatchers.Main) {
                            items = items.filter { it.path !in deleted }
                            selected.removeAll(deleted)
                            freedMsg = "Freed ${fmt(ctx, freed)} (${deleted.size} items)"
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
        )
    }
}
