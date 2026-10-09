package com.mixfia

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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

private data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MixfiaLibrary()
            }
        }
    }
}

private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun hasAudioPermission(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, audioPermission()
    ) == PackageManager.PERMISSION_GRANTED

private fun scanMusic(context: Context): List<AudioTrack> {
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

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun MixfiaLibrary() {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasAudioPermission(context)) }
    var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var search by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) tracks = scanMusic(context)
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) tracks = scanMusic(context)
    }

    val filteredTracks = tracks.filter {
        it.title.contains(search, ignoreCase = true) ||
            it.artist.contains(search, ignoreCase = true)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101116)
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Text(
                "MIXFIA",
                color = Color(0xFF75F0C0),
                fontSize = 27.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                "YOUR MUSIC. YOUR MIX.",
                color = Color.LightGray,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(22.dp))

            Text(
                "MUSIC LIBRARY",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${tracks.size} tracks found",
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
                    Modifier.fillMaxSize(),
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
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (tracks.isEmpty()) {
                            "No indexed music found on this device."
                        } else {
                            "No matching tracks."
                        },
                        color = Color.LightGray
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredTracks, key = { it.id }) { track ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    Color(0xFF20222B),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(14.dp),
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
        }
    }
}
