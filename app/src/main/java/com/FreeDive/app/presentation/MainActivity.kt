package com.FreeDive.app.presentation

import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.FreeDive.app.presentation.theme.FreeDiveTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

// SECTION 1 — SESSION RECORDS
private data class DiveRecord(
    val durationSeconds: Int,
    val maximumDepthMetres: Float,
    val averageHeartRate: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FreeDiveScreen() }
    }
}

@Composable
fun FreeDiveScreen() {
    // SECTION 2 — COLOURS AND NAVIGATION
    val background = Color(0xFF07151C)
    val accent = Color(0xFF63D8D2)
    val secondary = Color(0xFFA9C6CE)
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // SECTION 3 — SESSION STATE
    var sessionActive by remember { mutableStateOf(false) }
    var sessionEnded by remember { mutableStateOf(false) }
    var sessionStartMs by remember { mutableStateOf(0L) }
    var sessionSeconds by remember { mutableStateOf(0) }
    var isDiving by remember { mutableStateOf(false) }
    var currentDepth by remember { mutableStateOf(0f) }
    var currentHeartRate by remember { mutableStateOf(70) }
    var diveStartMs by remember { mutableStateOf(0L) }
    var diveElapsedSeconds by remember { mutableStateOf(0) }
    var currentDiveMax by remember { mutableStateOf(0f) }
    var lastDiveMax by remember { mutableStateOf(0f) }
    var sessionMax by remember { mutableStateOf(0f) }
    var lastDiveSeconds by remember { mutableStateOf(0) }
    var lastDiveAverageHeartRate by remember { mutableStateOf(0) }
    var heartRateTotal by remember { mutableStateOf(0) }
    var heartRateSamples by remember { mutableStateOf(0) }
    var intervalRemainingSeconds by remember { mutableStateOf(0) }
    var intervalEndMs by remember { mutableStateOf(0L) }
    var intervalActive by remember { mutableStateOf(false) }
    var intervalComplete by remember { mutableStateOf(false) }
    val completedDives = remember { mutableStateListOf<DiveRecord>() }

    // SECTION 4 — VIBRATION AND SESSION ACTIONS
    fun vibrateWatch() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun startSession() {
        completedDives.clear()
        sessionStartMs = SystemClock.elapsedRealtime()
        sessionSeconds = 0
        isDiving = false
        currentDepth = 0f
        currentHeartRate = 70
        diveStartMs = 0L
        diveElapsedSeconds = 0
        currentDiveMax = 0f
        lastDiveMax = 0f
        sessionMax = 0f
        lastDiveSeconds = 0
        lastDiveAverageHeartRate = 0
        heartRateTotal = 0
        heartRateSamples = 0
        intervalRemainingSeconds = 0
        intervalEndMs = 0L
        intervalActive = false
        intervalComplete = false
        sessionEnded = false
        sessionActive = true
    }

    fun endSession() {
        if (!sessionActive || isDiving) return
        sessionSeconds = ((SystemClock.elapsedRealtime() - sessionStartMs) / 1000L).toInt()
        sessionActive = false
        sessionEnded = true
        intervalActive = false
    }

    // SECTION 5 — DEPTH AND HEART RATE PROCESSING
    fun updateReading(depth: Float, heartRate: Int) {
        if (!sessionActive) return
        val now = SystemClock.elapsedRealtime()
        currentDepth = depth.coerceAtLeast(0f)
        currentHeartRate = heartRate

        if (!isDiving && currentDepth > 1.0f) {
            isDiving = true
            currentDiveMax = currentDepth
            diveStartMs = now
            diveElapsedSeconds = 0
            heartRateTotal = 0
            heartRateSamples = 0
            intervalActive = false
            intervalComplete = false
            vibrateWatch()
        }

        if (isDiving) {
            currentDiveMax = maxOf(currentDiveMax, currentDepth)
            sessionMax = maxOf(sessionMax, currentDepth)
            if (currentDepth > 1.0f) {
                heartRateTotal += heartRate
                heartRateSamples++
            } else {
                lastDiveMax = currentDiveMax
                lastDiveSeconds = ((now - diveStartMs) / 1000L).toInt()
                lastDiveAverageHeartRate =
                    if (heartRateSamples > 0) heartRateTotal / heartRateSamples else 0
                completedDives.add(
                    DiveRecord(lastDiveSeconds, lastDiveMax, lastDiveAverageHeartRate)
                )
                val intervalSeconds = lastDiveSeconds * 2
                intervalRemainingSeconds = intervalSeconds
                intervalEndMs = now + intervalSeconds * 1000L
                intervalActive = intervalSeconds > 0
                intervalComplete = intervalSeconds == 0
                isDiving = false
                vibrateWatch()
            }
        }
    }

    // SECTION 6 — SIMULATED SENSOR FEED (NOT REAL WATCH SENSORS)
    LaunchedEffect(sessionActive) {
        if (sessionActive) {
            val simulatedReadings = listOf(
                0.0f to 72, 0.3f to 73, 0.7f to 74,
                1.2f to 76, 2.5f to 80, 4.0f to 84,
                7.5f to 89, 10.0f to 92, 12.4f to 96,
                11.0f to 94, 8.0f to 90, 5.0f to 86,
                2.0f to 82, 1.2f to 80, 0.8f to 78
            )
            while (true) {
                for ((depth, heartRate) in simulatedReadings) {
                    updateReading(depth, heartRate)
                    delay(1500L)
                }
                updateReading(0.0f, 72)
                delay(40_000L)
            }
        }
    }

    // SECTION 7 — TIMERS
    LaunchedEffect(sessionActive) {
        while (sessionActive) {
            val now = SystemClock.elapsedRealtime()
            sessionSeconds = ((now - sessionStartMs) / 1000L).toInt()
            if (isDiving) {
                diveElapsedSeconds = ((now - diveStartMs) / 1000L).toInt()
            }
            if (intervalActive) {
                val secondsLeft = ceil(
                    (intervalEndMs - now).coerceAtLeast(0L) / 1000.0
                ).toInt()
                intervalRemainingSeconds = secondsLeft
                if (secondsLeft == 0) {
                    intervalActive = false
                    intervalComplete = true
                    vibrateWatch()
                }
            }
            delay(250L)
        }
    }

    // SECTION 8 — KEEP DIVE FACE VISIBLE WHILE UNDERWATER
    LaunchedEffect(isDiving, sessionActive) {
        if (sessionActive && isDiving && pagerState.currentPage != 1) {
            pagerState.scrollToPage(1)
        }
    }

    // SECTION 9 — ALL THREE PAGES
    FreeDiveTheme {
        AppScaffold {
            ScreenScaffold { contentPadding ->
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = sessionActive && !isDiving,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(background)
                            .padding(contentPadding)
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        when (page) {
                            // PAGE 0 — START / RETURN
                            0 -> {
                                Text(
                                    text = "FREEDIVE",
                                    color = accent,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = {
                                    if (!sessionActive) startSession()
                                    scope.launch { pagerState.animateScrollToPage(1) }
                                }) {
                                    Text(if (sessionActive) "RETURN TO DIVE" else "START SESSION")
                                }
                            }

                            // PAGE 1 — AUTOMATIC DIVE / SURFACE FACE
                            1 -> {
                                if (!sessionActive) {
                                    Text(
                                        text = "NO ACTIVE SESSION",
                                        color = secondary,
                                        fontSize = 12.sp
                                    )
                                } else if (isDiving) {
                                    // DIVE FACE — your compact layout
                                    Text(
                                        text = formatDuration(diveElapsedSeconds),
                                        color = secondary,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "DIVE TIME",
                                        color = secondary,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "%.1fM".format(currentDepth),
                                        color = accent,
                                        fontSize = 48.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Favorite,
                                            contentDescription = "Heart rate",
                                            tint = Color.Red,
                                            modifier = Modifier.height(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "$currentHeartRate BPM",
                                            color = Color.White,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                } else {
                                    // SURFACE FACE — your centred two-column layout
                                    Text(
                                        text = formatDuration(sessionSeconds),
                                        color = secondary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text("DIVE MAX", color = secondary, fontSize = 7.sp, maxLines = 1)
                                            Text("%.1f m".format(lastDiveMax), color = accent, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("AVG HR", color = secondary, fontSize = 7.sp, maxLines = 1)
                                            Text(
                                                if (lastDiveAverageHeartRate > 0) "$lastDiveAverageHeartRate BPM" else "-- BPM",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text("DIVE TIME", color = secondary, fontSize = 7.sp, maxLines = 1)
                                            Text(formatDuration(lastDiveSeconds), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        }
                                        Column(
                                            modifier = Modifier.weight(1.2f),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                when {
                                                    intervalActive -> "INTERVAL"
                                                    intervalComplete -> "COMPLETE"
                                                    else -> "NEXT INTERVAL"
                                                },
                                                color = secondary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                            Text(
                                                when {
                                                    intervalActive -> formatDuration(intervalRemainingSeconds)
                                                    intervalComplete -> "00:00"
                                                    else -> "--:--"
                                                },
                                                color = accent,
                                                fontSize = 30.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                when {
                                                    intervalActive -> "RECOVERY"
                                                    intervalComplete -> "TIME ELAPSED"
                                                    else -> "AWAITING DIVE"
                                                },
                                                color = secondary,
                                                fontSize = 8.sp,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("SESSION MAX", color = secondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text("%.1f m".format(sessionMax), color = accent, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    }
                                }
                            }

                            // PAGE 2 — END SESSION / RESULTS
                            2 -> {
                                Text(
                                    text = if (sessionEnded) "SESSION ENDED" else "END SESSION?",
                                    color = accent,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                if (sessionEnded) {
                                    Text("${completedDives.size} DIVES", color = secondary, fontSize = 10.sp)
                                    Text("TIME  ${formatDuration(sessionSeconds)}", color = Color.White, fontSize = 14.sp)
                                    Text("MAX  ${"%.1f".format(sessionMax)} m", color = accent, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(onClick = {
                                        scope.launch { pagerState.animateScrollToPage(0) }
                                    }) { Text("NEW SESSION") }
                                } else {
                                    Text("Swipe right to return", color = secondary, fontSize = 10.sp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(onClick = { endSession() }, enabled = sessionActive && !isDiving) {
                                        Text("END SESSION")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// SECTION 10 — TIMER FORMATTING
fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
