package com.mixfia

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MixfiaTab(val label: String, val symbol: String) {
    LIBRARY("Library", "♪"),
    MIXER("Mix", "◈"),
    EXPLORE("Explore", "⌕"),
    DOWNLOADS("Downloads", "↓"),
    SETTINGS("Settings", "⚙")
}

@Composable
internal fun MixfiaApp() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("mixfia_settings", Context.MODE_PRIVATE)
    }
    var selectedTab by rememberSaveable { mutableStateOf(MixfiaTab.LIBRARY) }
    var wifiOnly by remember { mutableStateOf(preferences.getBoolean("wifi_only", true)) }
    var rescanToken by remember { mutableIntStateOf(0) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF75F0C0))) {
        Scaffold(
            containerColor = Color(0xFF101116),
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF171920)) {
                    MixfiaTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Text(tab.symbol, fontSize = 18.sp) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF75F0C0),
                                selectedTextColor = Color(0xFF75F0C0),
                                indicatorColor = Color(0xFF29372F),
                                unselectedIconColor = Color.LightGray,
                                unselectedTextColor = Color.LightGray
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (selectedTab) {
                    MixfiaTab.LIBRARY -> MixfiaLibrary(rescanToken = rescanToken)
                    MixfiaTab.MIXER -> DjMixerScreen()
                    MixfiaTab.EXPLORE -> OnlineCatalogScreen(wifiOnly = wifiOnly)
                    MixfiaTab.DOWNLOADS -> DownloadsScreen()
                    MixfiaTab.SETTINGS -> SettingsScreen(
                        wifiOnly = wifiOnly,
                        onWifiOnlyChanged = { enabled ->
                            wifiOnly = enabled
                            preferences.edit().putBoolean("wifi_only", enabled).apply()
                        },
                        onRescan = {
                            rescanToken++
                            selectedTab = MixfiaTab.LIBRARY
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineCatalogScreen(wifiOnly: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var mediaType by rememberSaveable { mutableStateOf(CatalogMediaType.ALL) }
    var results by remember { mutableStateOf(emptyList<CatalogItem>()) }
    var searching by remember { mutableStateOf(false) }
    var pendingIdentifier by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun searchCatalog() {
        if (query.isBlank() || searching) return
        scope.launch {
            searching = true
            error = null
            message = null
            try {
                results = ArchiveCatalog.search(query, mediaType)
            } catch (exception: Exception) {
                error = exception.message ?: "Search is unavailable. Check your connection and try again."
            } finally {
                searching = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Text("Explore", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Openly licensed audio and video", color = Color.LightGray, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Title, artist, or film") },
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { searchCatalog() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF75F0C0),
                    unfocusedBorderColor = Color.DarkGray,
                    cursorColor = Color(0xFF75F0C0)
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { searchCatalog() },
                enabled = query.isNotBlank() && !searching,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF75F0C0),
                    contentColor = Color.Black
                ),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Text(if (searching) "..." else "SEARCH")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogMediaType.entries.forEach { type ->
                FilterChip(
                    selected = mediaType == type,
                    onClick = { mediaType = type },
                    label = { Text(type.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF29372F),
                        selectedLabelColor = Color(0xFF75F0C0)
                    )
                )
            }
        }
        message?.let {
            Text(it, color = Color(0xFF75F0C0), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        error?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        when {
            searching -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF75F0C0))
            }
            results.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "Search the Internet Archive catalog."
                    else "No matching openly licensed items.",
                    color = Color.LightGray
                )
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(results, key = { it.identifier }) { item ->
                    CatalogResultRow(
                        item = item,
                        downloading = pendingIdentifier == item.identifier,
                        downloadBusy = pendingIdentifier != null,
                        onDownload = {
                            scope.launch {
                                pendingIdentifier = item.identifier
                                message = null
                                error = null
                                try {
                                    ArchiveCatalog.enqueueDownload(context, item, wifiOnly)
                                    message = "Download added. Progress appears in Downloads."
                                } catch (exception: Exception) {
                                    error = exception.message ?: "This item could not be downloaded."
                                } finally {
                                    pendingIdentifier = null
                                }
                            }
                        },
                        onSource = {
                            openWebPage(context, "https://archive.org/details/${Uri.encode(item.identifier)}")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogResultRow(
    item: CatalogItem,
    downloading: Boolean,
    downloadBusy: Boolean,
    onDownload: () -> Unit,
    onSource: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF20222B), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                listOf(item.creator, item.year, item.mediaType.label).filter { it.isNotBlank() }.joinToString(" · "),
                color = Color.LightGray,
                fontSize = 12.sp,
                maxLines = 2
            )
            Text(
                licenseLabel(item.licenseUrl),
                color = Color(0xFF75F0C0),
                fontSize = 11.sp
            )
            Row {
                TextButton(onClick = onSource, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("SOURCE", fontSize = 11.sp)
                }
                Button(
                    onClick = onDownload,
                    enabled = !downloadBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF75F0C0),
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(if (downloading) "CHECKING" else "DOWNLOAD", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun DownloadsScreen() {
    val context = LocalContext.current
    var downloads by remember { mutableStateOf(emptyList<DownloadRecord>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        do {
            downloads = ArchiveCatalog.loadDownloads(context)
            if (downloads.any {
                    it.status == DownloadManager.STATUS_PENDING ||
                        it.status == DownloadManager.STATUS_RUNNING
                }
            ) delay(1500)
        } while (downloads.any {
            it.status == DownloadManager.STATUS_PENDING ||
                it.status == DownloadManager.STATUS_RUNNING
        })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Audio and video saved on this device", color = Color.LightGray, fontSize = 13.sp)
            }
            TextButton(onClick = { refreshKey++ }) { Text("REFRESH") }
        }
        notice?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
        Spacer(Modifier.height(12.dp))
        if (downloads.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Your downloads will appear here.", color = Color.LightGray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(downloads, key = { it.id }) { download ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF20222B), RoundedCornerShape(8.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(download.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(
                                "${download.mediaType.label} · ${downloadStatus(download)}",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                            Text("CC / public domain · Internet Archive", color = Color(0xFF75F0C0), fontSize = 11.sp)
                        }
                        if (download.status == DownloadManager.STATUS_SUCCESSFUL) {
                            TextButton(onClick = {
                                if (!ArchiveCatalog.openDownload(context, download.id)) {
                                    notice = "No compatible media player is installed."
                                }
                            }) {
                                Text("OPEN")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    wifiOnly: Boolean,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onRescan: () -> Unit
) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 22.dp)) {
        Text("Settings", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onWifiOnlyChanged(!wifiOnly) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi only downloads", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Avoid mobile data for online media", color = Color.LightGray, fontSize = 12.sp)
            }
            Switch(checked = wifiOnly, onCheckedChange = onWifiOnlyChanged)
        }
        HorizontalDivider(color = Color.DarkGray)
        TextButton(
            onClick = onRescan,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Text("RESCAN MUSIC LIBRARY", modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider(color = Color.DarkGray)
        Column(Modifier.padding(vertical = 14.dp)) {
            Text("Online catalog", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                "Internet Archive results are limited to public-domain and Creative Commons BY / BY-SA items. Licenses are checked again before a download starts.",
                color = Color.LightGray,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            TextButton(
                onClick = { openWebPage(context, "https://archive.org") },
                contentPadding = PaddingValues(horizontal = 0.dp)
            ) {
                Text("VISIT INTERNET ARCHIVE")
            }
        }
        HorizontalDivider(color = Color.DarkGray)
        Text("Mixfia  $version", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

private fun downloadStatus(download: DownloadRecord): String = when (download.status) {
    DownloadManager.STATUS_PENDING -> "Queued"
    DownloadManager.STATUS_RUNNING -> "Downloading"
    DownloadManager.STATUS_PAUSED -> if (
        download.reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI
    ) "Waiting for Wi-Fi" else "Paused"
    DownloadManager.STATUS_SUCCESSFUL -> "Complete"
    else -> "Download failed"
}

private fun licenseLabel(licenseUrl: String): String {
    val licensePath = Uri.parse(licenseUrl).path.orEmpty().trimEnd('/')
    return when {
        licensePath.startsWith("/licenses/by-sa/") -> "Creative Commons BY-SA ${licensePath.substringAfterLast('/')}"
        licensePath.startsWith("/licenses/by/") -> "Creative Commons BY ${licensePath.substringAfterLast('/')}"
        licensePath.startsWith("/publicdomain/zero/") -> "Public domain · CC0"
        else -> "Public domain"
    }
}

private fun openWebPage(context: Context, address: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
    }
}