package com.mixfia

import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MixerMint = Color(0xFF75F0C0)
private val MixerPanel = Color(0xFF20222B)

@Composable
internal fun DjMixerScreen() {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasAudioPermission(context)) }
    var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var loadDeck by remember { mutableStateOf<Int?>(null) }
    var crossfader by remember { mutableFloatStateOf(0f) }
    val leftDeck = remember(context) { DeckPlayer(context) }
    val rightDeck = remember(context) { DeckPlayer(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionGranted = it }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            tracks = withContext(Dispatchers.IO) { scanMusic(context) }
        }
    }

    LaunchedEffect(leftDeck, rightDeck) {
        while (true) {
            leftDeck.refreshPosition()
            rightDeck.refreshPosition()
            delay(100)
        }
    }

    DisposableEffect(leftDeck, rightDeck) {
        onDispose {
            leftDeck.release()
            rightDeck.release()
        }
    }

    val leftGain = kotlin.math.cos((crossfader + 1f) * Math.PI / 4.0).toFloat()
    val rightGain = kotlin.math.sin((crossfader + 1f) * Math.PI / 4.0).toFloat()
    LaunchedEffect(crossfader) {
        leftDeck.setCrossfadeGain(leftGain)
        rightDeck.setCrossfadeGain(rightGain)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101116)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("DJ MIXER", color = MixerMint, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("Two-deck playback", color = Color.LightGray, fontSize = 12.sp)

            if (!permissionGranted) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Allow access to your music", color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { permissionLauncher.launch(audioPermission()) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MixerMint,
                                contentColor = Color.Black
                            )
                        ) {
                            Text("SCAN MY MUSIC")
                        }
                    }
                }
            } else if (tracks.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No music found on this device.", color = Color.LightGray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    item {
                        DeckPanel(
                            name = "DECK A",
                            deck = leftDeck,
                            onLoad = { loadDeck = 0 }
                        )
                    }
                    item {
                        DeckPanel(
                            name = "DECK B",
                            deck = rightDeck,
                            onLoad = { loadDeck = 1 }
                        )
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MixerPanel, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("A", color = MixerMint, fontWeight = FontWeight.Bold)
                        Text("CROSSFADER", color = Color.LightGray, fontSize = 11.sp)
                        Text("B", color = MixerMint, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = crossfader,
                        onValueChange = { crossfader = it },
                        valueRange = -1f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = MixerMint,
                            activeTrackColor = MixerMint,
                            inactiveTrackColor = Color.DarkGray
                        )
                    )
                }
            }
        }
    }

    loadDeck?.let { deckIndex ->
        AlertDialog(
            onDismissRequest = { loadDeck = null },
            title = { Text(if (deckIndex == 0) "Load Deck A" else "Load Deck B") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(tracks, key = { it.id }) { track ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    (if (deckIndex == 0) leftDeck else rightDeck).load(track)
                                    loadDeck = null
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(track.title, color = Color.White, maxLines = 1)
                            Text(track.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { loadDeck = null }) { Text("CLOSE") }
            },
            containerColor = Color(0xFF20222B),
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}

@Composable
private fun DeckPanel(name: String, deck: DeckPlayer, onLoad: () -> Unit) {
    val context = LocalContext.current
    val track = deck.track
    LaunchedEffect(track?.id, deck.waveformGeneration) {
        deck.waveform = if (track == null) {
            emptyList()
        } else {
            decodeWaveform(context, track)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MixerPanel, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, color = MixerMint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    track?.title ?: "No track loaded",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    deck.error ?: track?.artist ?: "Choose music from your library",
                    color = if (deck.error != null) Color(0xFFFF8A80) else Color.LightGray,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            OutlinedButton(onClick = onLoad, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("LOAD", color = MixerMint, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        WaveformView(deck.waveform, deck.position, deck.duration, Modifier.fillMaxWidth())
        Slider(
            value = deck.position.coerceIn(0L, deck.duration.coerceAtLeast(1L)).toFloat(),
            onValueChange = { deck.seekTo(it.toLong()) },
            valueRange = 0f..deck.duration.coerceAtLeast(1L).toFloat(),
            enabled = deck.prepared && deck.duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = MixerMint,
                activeTrackColor = MixerMint,
                inactiveTrackColor = Color.DarkGray
            )
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDuration(deck.position), color = Color.LightGray, fontSize = 10.sp)
            Text(formatDuration(deck.duration), color = Color.LightGray, fontSize = 10.sp)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { deck.storeCue() },
                enabled = deck.prepared,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) { Text("SET CUE", fontSize = 10.sp) }
            OutlinedButton(
                onClick = { deck.jumpToCue() },
                enabled = deck.prepared,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) { Text("CUE", fontSize = 10.sp) }
            OutlinedButton(
                onClick = { deck.toggleLoop() },
                enabled = deck.prepared,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(if (deck.looping) "EXIT LOOP" else "4 BEAT LOOP", fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { deck.togglePlayback() },
                enabled = deck.prepared,
                contentPadding = PaddingValues(horizontal = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MixerMint,
                    contentColor = Color.Black
                )
            ) {
                Text(if (deck.playing) "PAUSE" else "PLAY", fontSize = 11.sp)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(48.dp)) {
                Text("TARGET", color = Color.LightGray, fontSize = 9.sp)
                Text("REF ${deck.referenceBpm.roundToInt()}", color = MixerMint, fontSize = 9.sp)
            }
            Slider(
                modifier = Modifier.weight(1f),
                value = deck.bpm,
                onValueChange = { deck.updateTargetBpm(it) },
                valueRange = 60f..180f,
                steps = 119,
                enabled = deck.prepared,
                colors = SliderDefaults.colors(
                    thumbColor = MixerMint,
                    activeTrackColor = MixerMint,
                    inactiveTrackColor = Color.DarkGray
                )
            )
            Text(deck.bpm.roundToInt().toString(), color = MixerMint, fontSize = 12.sp)
            TextButton(onClick = { deck.tapTempo() }, enabled = deck.prepared) {
                Text("TAP", color = MixerMint, fontSize = 10.sp)
            }
        }

        if (deck.prepared && !deck.equalizerAvailable) {
            Text("EQ unavailable on this device", color = Color.LightGray, fontSize = 10.sp)
        }
        val eqEnabled = deck.prepared && deck.equalizerAvailable
        EqualizerControl("LOW", deck.low, eqEnabled) { deck.setEqualizer(low = it) }
        EqualizerControl("MID", deck.mid, eqEnabled) { deck.setEqualizer(mid = it) }
        EqualizerControl("HIGH", deck.high, eqEnabled) { deck.setEqualizer(high = it) }
    }
}

@Composable
private fun EqualizerControl(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.width(38.dp))
        Slider(
            modifier = Modifier.weight(1f).height(32.dp),
            value = value,
            onValueChange = onChange,
            valueRange = -1f..1f,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MixerMint,
                activeTrackColor = MixerMint,
                inactiveTrackColor = Color.DarkGray
            )
        )
        Text("${(value * 12).roundToInt()} dB", color = Color.LightGray, fontSize = 10.sp)
    }
}

@Composable
private fun WaveformView(
    peaks: List<Float>,
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier
            .height(44.dp)
            .background(Color(0xFF15171D), RoundedCornerShape(4.dp))
    ) {
        val centerY = size.height / 2f
        drawLine(Color.DarkGray, androidx.compose.ui.geometry.Offset(0f, centerY), androidx.compose.ui.geometry.Offset(size.width, centerY))
        if (peaks.isNotEmpty()) {
            val step = size.width / peaks.size
            peaks.forEachIndexed { index, peak ->
                val height = max(2f, peak * size.height * 0.88f)
                val x = index * step + step / 2f
                drawLine(
                    color = if (duration > 0 && index.toFloat() / peaks.size <= position.toFloat() / duration) {
                        MixerMint
                    } else {
                        Color(0xFF60766E)
                    },
                    start = androidx.compose.ui.geometry.Offset(x, centerY - height / 2f),
                    end = androidx.compose.ui.geometry.Offset(x, centerY + height / 2f),
                    strokeWidth = max(1f, step * 0.68f),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private class DeckPlayer(private val context: Context) {
    var track by mutableStateOf<AudioTrack?>(null)
        private set
    var prepared by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var cuePosition by mutableLongStateOf(0L)
        private set
    var looping by mutableStateOf(false)
        private set
    var bpm by mutableFloatStateOf(120f)
        private set
    var referenceBpm by mutableFloatStateOf(120f)
        private set
    var low by mutableFloatStateOf(0f)
        private set
    var mid by mutableFloatStateOf(0f)
        private set
    var high by mutableFloatStateOf(0f)
        private set
    var equalizerAvailable by mutableStateOf(false)
        private set
    var waveform by mutableStateOf(emptyList<Float>())
    var waveformGeneration by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var crossfadeGain = 1f
    private var loopStart = 0L
    private var loopEnd = 0L
    private var lastTempoTap = 0L
    private val tempoTapIntervals = mutableListOf<Long>()

    fun load(audioTrack: AudioTrack) {
        releasePlayer()
        track = audioTrack
        prepared = false
        playing = false
        position = 0L
        duration = audioTrack.duration
        cuePosition = 0L
        looping = false
        bpm = 120f
        referenceBpm = 120f
        lastTempoTap = 0L
        tempoTapIntervals.clear()
        low = 0f
        mid = 0f
        high = 0f
        waveform = emptyList()
        waveformGeneration++
        error = null

        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mediaPlayer.setDataSource(
                context,
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, audioTrack.id)
            )
            mediaPlayer.setOnPreparedListener { readyPlayer ->
                prepared = true
                duration = readyPlayer.duration.toLong()
                equalizer = runCatching {
                    Equalizer(0, readyPlayer.audioSessionId).apply { enabled = true }
                }.getOrNull()
                equalizerAvailable = equalizer != null
                applyBpm()
                applyEqualizer()
                updateVolume()
            }
            mediaPlayer.setOnCompletionListener {
                if (looping) {
                    it.seekTo(loopStart.toInt())
                    it.start()
                } else {
                    playing = false
                    position = duration
                }
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                playing = false
                error = "Unable to play this track."
                true
            }
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            mediaPlayer.release()
            player = null
            error = "Unable to prepare this track."
        }
    }

    fun togglePlayback() {
        val activePlayer = player ?: return
        if (!prepared) return
        runCatching {
            if (playing) activePlayer.pause() else activePlayer.start()
            playing = !playing
        }.onFailure { error = "Playback could not be changed." }
    }

    fun seekTo(milliseconds: Long) {
        position = milliseconds.coerceIn(0L, duration.coerceAtLeast(0L))
        if (prepared) runCatching { player?.seekTo(position.toInt()) }
    }

    fun refreshPosition() {
        val activePlayer = player ?: return
        if (!prepared || !playing) return
        runCatching { activePlayer.currentPosition.toLong() }
            .onSuccess { current ->
                if (looping && current >= loopEnd) {
                    activePlayer.seekTo(loopStart.toInt())
                    position = loopStart
                } else {
                    position = current
                }
            }
    }

    fun storeCue() {
        cuePosition = position
    }

    fun jumpToCue() {
        seekTo(cuePosition)
    }

    fun toggleLoop() {
        if (looping) {
            looping = false
            return
        }
        val loopLength = (240_000f / bpm).roundToInt().toLong().coerceAtLeast(1L)
        loopStart = position
        loopEnd = min(duration, loopStart + loopLength)
        if (loopEnd <= loopStart) {
            loopStart = 0L
            loopEnd = min(duration, loopLength)
        }
        looping = loopEnd > loopStart
        if (looping && position >= loopEnd) seekTo(loopStart)
    }

    fun updateTargetBpm(value: Float) {
        bpm = value.coerceIn(60f, 180f)
        applyBpm()
    }

    fun tapTempo() {
        val now = SystemClock.elapsedRealtime()
        if (lastTempoTap > 0L) {
            val interval = now - lastTempoTap
            if (interval in 250L..2_000L) {
                tempoTapIntervals.add(interval)
                if (tempoTapIntervals.size > 4) tempoTapIntervals.removeAt(0)
                val sortedIntervals = tempoTapIntervals.sorted()
                val medianInterval = sortedIntervals[sortedIntervals.size / 2]
                referenceBpm = (60_000f / medianInterval).coerceIn(60f, 180f)
                bpm = referenceBpm
                applyBpm()
            } else {
                tempoTapIntervals.clear()
            }
        }
        lastTempoTap = now
    }

    fun setEqualizer(low: Float? = null, mid: Float? = null, high: Float? = null) {
        low?.let { this.low = it.coerceIn(-1f, 1f) }
        mid?.let { this.mid = it.coerceIn(-1f, 1f) }
        high?.let { this.high = it.coerceIn(-1f, 1f) }
        applyEqualizer()
    }

    fun setCrossfadeGain(gain: Float) {
        crossfadeGain = gain.coerceIn(0f, 1f)
        updateVolume()
    }

    fun release() {
        releasePlayer()
    }

    private fun applyBpm() {
        if (!prepared) return
        runCatching {
            val activePlayer = player ?: return
            activePlayer.playbackParams = activePlayer.playbackParams
                .setSpeed((bpm / referenceBpm).coerceIn(0.5f, 2f))
                .setPitch(1f)
        }.onFailure { error = "This device does not support tempo control." }
    }

    private fun applyEqualizer() {
        val activeEqualizer = equalizer ?: return
        runCatching {
            val range = activeEqualizer.bandLevelRange
            val bandCount = activeEqualizer.numberOfBands.toInt()
            for (index in 0 until bandCount) {
                val frequencyHz = activeEqualizer.getCenterFreq(index.toShort()) / 1000
                val value = when {
                    frequencyHz < 250 -> low
                    frequencyHz < 4_000 -> mid
                    else -> high
                }
                val level = if (value < 0f) {
                    range[0] * -value
                } else {
                    range[1] * value
                }
                activeEqualizer.setBandLevel(index.toShort(), level.roundToInt().toShort())
            }
        }
    }

    private fun updateVolume() {
        runCatching { player?.setVolume(crossfadeGain, crossfadeGain) }
    }

    private fun releasePlayer() {
        runCatching { equalizer?.release() }
        equalizer = null
        equalizerAvailable = false
        runCatching { player?.release() }
        player = null
    }
}

private suspend fun decodeWaveform(context: Context, track: AudioTrack): List<Float> =
    withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
            extractor.setDataSource(context, uri, null)
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext emptyList()
            val sourceFormat = extractor.getTrackFormat(audioTrackIndex)
            val mimeType = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext emptyList()
            val sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val durationUs = sourceFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            val peaks = FloatArray(180)
            extractor.selectTrack(audioTrackIndex)
            codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var channelsOut = channels
            var sampleRateOut = sampleRate
            val startedAt = SystemClock.elapsedRealtime()

            while (!outputEnded && SystemClock.elapsedRealtime() - startedAt < 45_000L) {
                coroutineContext.ensureActive()
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        channelsOut = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        sampleRateOut = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(1)
                        pcmEncoding = runCatching {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pcm = outputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val bytesPerSample = when (pcmEncoding) {
                                AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                else -> 2
                            }
                            val frameSize = bytesPerSample * channelsOut
                            val frameCount = if (frameSize > 0) pcm.remaining() / frameSize else 0
                            for (frame in 0 until frameCount) {
                                var framePeak = 0f
                                repeat(channelsOut) { channel ->
                                    val sampleOffset = frame * frameSize + channel * bytesPerSample
                                    val amplitude = when (pcmEncoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> abs(pcm.getFloat(sampleOffset))
                                        AudioFormat.ENCODING_PCM_32BIT -> abs(pcm.getInt(sampleOffset).toFloat() / Int.MAX_VALUE)
                                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                            val lowByte = pcm.get(sampleOffset).toInt() and 0xFF
                                            val middleByte = pcm.get(sampleOffset + 1).toInt() and 0xFF
                                            val highByte = pcm.get(sampleOffset + 2).toInt().toByte().toInt()
                                            abs((lowByte or (middleByte shl 8) or (highByte shl 16)).toFloat() / 8_388_608f)
                                        }
                                        AudioFormat.ENCODING_PCM_8BIT -> abs((pcm.get(sampleOffset).toInt() and 0xFF) - 128) / 128f
                                        else -> abs(pcm.getShort(sampleOffset).toInt() / 32768f)
                                    }
                                    framePeak = max(framePeak, amplitude)
                                }
                                val timeUs = bufferInfo.presentationTimeUs + frame * 1_000_000L / sampleRateOut
                                val bin = ((timeUs.toDouble() / durationUs) * peaks.size)
                                    .toInt().coerceIn(0, peaks.lastIndex)
                                peaks[bin] = max(peaks[bin], framePeak.coerceIn(0f, 1f))
                            }
                        }
                        outputEnded = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (peaks.any { it > 0f }) peaks.toList() else emptyList()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }