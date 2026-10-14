package com.mixfia

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MixfiaApp()
        }
    }
}

internal fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

internal fun hasAudioPermission(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, audioPermission()
    ) == PackageManager.PERMISSION_GRANTED

internal fun scanMusic(context: Context): List<AudioTrack> {
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION
    )
    val tracks = mutableListOf<AudioTrack>()

    context.contentResolver.query(
        collection,
        projection,
        "${MediaStore.Audio.Media.IS_MUSIC} != 0",
        null,
        "${MediaStore.Audio.Media.TITLE} ASC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

        while (cursor.moveToNext()) {
            tracks.add(
                AudioTrack(
                    id = cursor.getLong(idColumn),
                    title = cursor.getString(titleColumn) ?: "Unknown title",
                    artist = cursor.getString(artistColumn)
                        ?.takeUnless { it == "<unknown>" } ?: "Unknown artist",
                    duration = cursor.getLong(durationColumn)
                )
            )
        }
    }
    return tracks
}

internal fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
internal fun MixfiaLibrary(rescanToken: Int = 0) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasAudioPermission(context)) }
    var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var search by remember { mutableStateOf("") }
    var selectedTrack by remember { mutableStateOf<AudioTrack?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isSeeking by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableStateOf(0L) }
    var playbackDuration by remember { mutableStateOf(0L) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    val latestTracks by rememberUpdatedState(tracks)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
    }

    LaunchedEffect(permissionGranted, rescanToken) {
        if (permissionGranted) {
            tracks = withContext(Dispatchers.IO) { scanMusic(context) }
        }
    }

    DisposableEffect(selectedTrack?.id) {
        val track = selectedTrack
        if (track == null) {
            player = null
            isPrepared = false
            onDispose { }
        } else {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            isPrepared = false
            isPlaying = false
            playbackPosition = 0L
            playbackDuration = track.duration
            playbackError = null

            try {
                mediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mediaPlayer.setDataSource(
                    context,
                    ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        track.id
                    )
                )
                mediaPlayer.setOnPreparedListener { preparedPlayer ->
                    isPrepared = true
                    playbackDuration = preparedPlayer.duration.toLong()
                    preparedPlayer.start()
                    isPlaying = true
                }
                mediaPlayer.setOnCompletionListener {
                    val playlist = latestTracks
                    val currentIndex = playlist.indexOfFirst { it.id == track.id }
                    if (currentIndex >= 0 && playlist.size > 1) {
                        selectedTrack = playlist[(currentIndex + 1) % playlist.size]
                    } else {
                        isPlaying = false
                        playbackPosition = 0L
                        it.seekTo(0)
                    }
                }
                mediaPlayer.setOnErrorListener { _, _, _ ->
                    isPlaying = false
                    playbackError = "Unable to play this track."
                    true
                }
                mediaPlayer.prepareAsync()
            } catch (_: Exception) {
                isPlaying = false
                playbackError = "Unable to play this track."
            }

            onDispose {
                mediaPlayer.release()
            }
        }
    }

    LaunchedEffect(player, isPrepared, isPlaying) {
        val activePlayer = player
        if (activePlayer != null && isPrepared && isPlaying) {
            while (true) {
                if (!isSeeking) playbackPosition = activePlayer.currentPosition.toLong()
                delay(500)
            }
        }
    }

    val filteredTracks = tracks.filter {
        it.title.contains(search, ignoreCase = true) ||
            it.artist.contains(search, ignoreCase = true)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101116)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Text(
                "MIXFIA",
                color = Color(0xFF75F0C0),
                fontSize = 28.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "YOUR MUSIC. YOUR MIX.",
                color = Color.LightGray,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(22.dp))

            Text(
                "Music library",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${tracks.size} ${if (tracks.size == 1) "track" else "tracks"}",
                color = Color(0xFF75F0C0),
                fontSize = 13.sp
            )

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search songs or artists") },
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF75F0C0),
                    unfocusedBorderColor = Color.DarkGray,
                    cursorColor = Color(0xFF75F0C0)
                )
            )

            Spacer(Modifier.height(12.dp))

            if (!permissionGranted) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Allow access to your music",
                            color = Color.White
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                permissionLauncher.launch(audioPermission())
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF75F0C0),
                                contentColor = Color.Black
                            )
                        ) {
                            Text("SCAN MY MUSIC")
                        }
                    }
                }
            } else if (filteredTracks.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (tracks.isEmpty()) {
                            "No music found on this device."
                        } else {
                            "No matching tracks."
                        },
                        color = Color.LightGray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTracks, key = { it.id }) { track ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedTrack = track }
                                .background(
                                    if (selectedTrack?.id == track.id) {
                                        Color(0xFF29372F)
                                    } else {
                                        Color(0xFF20222B)
                                    },
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    track.artist,
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                formatDuration(track.duration),
                                color = Color(0xFF75F0C0),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            selectedTrack?.let { track ->
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF20222B), RoundedCornerShape(10.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        track.title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        playbackError ?: track.artist,
                        color = if (playbackError != null) Color(0xFFFF8A80) else Color.LightGray,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                    Slider(
                        value = playbackPosition.coerceIn(0L, playbackDuration.coerceAtLeast(1L)).toFloat(),
                        onValueChange = {
                            isSeeking = true
                            playbackPosition = it.toLong()
                        },
                        onValueChangeFinished = {
                            player?.seekTo(playbackPosition.toInt())
                            isSeeking = false
                        },
                        valueRange = 0f..playbackDuration.coerceAtLeast(1L).toFloat(),
                        enabled = isPrepared && playbackDuration > 0,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF75F0C0),
                            activeTrackColor = Color(0xFF75F0C0),
                            inactiveTrackColor = Color.DarkGray
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            formatDuration(playbackPosition),
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val index = tracks.indexOfFirst { it.id == track.id }
                                    if (index >= 0 && tracks.size > 1) {
                                        selectedTrack = tracks[(index - 1 + tracks.size) % tracks.size]
                                    }
                                },
                                enabled = tracks.size > 1
                            ) {
                                Text("PREV", color = Color.LightGray, fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    val activePlayer = player
                                    if (activePlayer != null && isPrepared) {
                                        if (isPlaying) {
                                            activePlayer.pause()
                                            isPlaying = false
                                        } else {
                                            activePlayer.start()
                                            isPlaying = true
                                        }
                                    }
                                },
                                enabled = isPrepared,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF75F0C0),
                                    contentColor = Color.Black
                                ),
                                contentPadding = PaddingValues(horizontal = 20.dp)
                            ) {
                                Text(if (isPlaying) "PAUSE" else "PLAY")
                            }
                            TextButton(
                                onClick = {
                                    val index = tracks.indexOfFirst { it.id == track.id }
                                    if (index >= 0 && tracks.size > 1) {
                                        selectedTrack = tracks[(index + 1) % tracks.size]
                                    }
                                },
                                enabled = tracks.size > 1
                            ) {
                                Text("NEXT", color = Color.LightGray, fontSize = 11.sp)
                            }
                        }
                        Text(
                            formatDuration(playbackDuration),
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
