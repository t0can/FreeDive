package com.FreeDive.app.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.foundation.layout.padding
import android.view.WindowManager

// SECTION 1 — SESSION RECORDS LIVE IN DiveSessionService.kt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            androidx.compose.runtime.LaunchedEffect(DiveState.sessionActive) {
                if (DiveState.sessionActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            FreeDiveScreen(
                onSessionActiveChanged = { active ->
                    if (active) {
                        window.addFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        )
                    } else {
                        window.clearFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun FreeDiveScreen(
    onSessionActiveChanged: (Boolean) -> Unit
) {
    // SECTION 2 — COLOURS AND NAVIGATION
    val background = Color(0xFF07151C)
    val accent = Color(0xFF63D8D2)
    val secondary = Color(0xFFA9C6CE)
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = if (DiveState.sessionActive) 1 else 0, pageCount = { 4 })
    val scope = rememberCoroutineScope()

    // SECTION 3 — SERVICE-OWNED STATE (READ BY COMPOSE)
    val state = DiveState
    val sessionActive = state.sessionActive
    val sessionEnded = state.sessionEnded
    val sessionSeconds = state.sessionSeconds
    val isDiving = state.isDiving
    val currentDepth = state.currentDepth
    val currentHeartRate = state.currentHeartRate
    val diveElapsedSeconds = state.diveElapsedSeconds
    val lastDiveMax = state.lastDiveMax
    val sessionMax = state.sessionMax
    val lastDiveSeconds = state.lastDiveSeconds
    val lastDiveAverageHeartRate = state.lastDiveAverageHeartRate
    val intervalActive = state.intervalActive
    val intervalComplete = state.intervalComplete
    val intervalRemainingSeconds = state.intervalRemainingSeconds
    val completedDives = state.completedDives
    val pressureHpa = state.pressureHpa
    val referencePressureHpa = state.referencePressureHpa
    val pressureSensorRunning = state.pressureSensorRunning
    val pressureHistory = state.pressureHistory
    val maxPositiveDeltaHpa = state.maxPositiveDeltaHpa
    val recordingSeconds = state.recordingSeconds
    val hpaPerMetre = DiveState.HPA_PER_METRE
    val heartPermission = if (Build.VERSION.SDK_INT >= 36)
        "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
    var heartPermissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, heartPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val heartPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> heartPermissionGranted = granted }

    // SECTION 3A — NOTIFICATION PERMISSION (ANDROID 13+)
    // Request while the app is visible. Recording can still run if denied.
    val notificationPermission = Manifest.permission.POST_NOTIFICATIONS
    var notificationPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, notificationPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationPermissionGranted = granted }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionGranted) {
            notificationPermissionLauncher.launch(notificationPermission)
        }
    }

    // SECTION 4 — SESSION COMMANDS
    fun startSession() {
        ContextCompat.startForegroundService(context,
            android.content.Intent(context, DiveSessionService::class.java).apply {
                action = DiveSessionService.ACTION_START
            })
        // Ask for heart permission before recording on the next session if needed.
        if (!heartPermissionGranted) heartPermissionLauncher.launch(heartPermission)
    }
    fun endSession() {
        context.startService(android.content.Intent(context, DiveSessionService::class.java).apply {
            action = DiveSessionService.ACTION_STOP
        })
    }

    // SECTION 5 — SENSOR COLLECTION, DEPTH AND TIMERS NOW RUN IN DiveSessionService.kt
    // SECTION 6 — PRESSURE HISTORY NOW RUNS IN DiveSessionService.kt
    // SECTION 7 — SESSION CLOCK NOW RUNS IN DiveSessionService.kt
    // SECTION 8 — KEEP DIVE FACE VISIBLE WHILE UNDERWATER
    LaunchedEffect(isDiving, sessionActive) {
        if (sessionActive && isDiving && pagerState.currentPage != 1) pagerState.scrollToPage(1)
    }

    // SECTION 9 — ALL FOUR PAGES (LIVE DEPTH + DIAGNOSTIC)
    FreeDiveTheme {
        AppScaffold {
            ScreenScaffold { contentPadding ->
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = !isDiving,
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
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("EXPERIMENTAL • NOT FOR DIVING", color = secondary, fontSize = 8.sp, maxLines = 1)
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(onClick = {
                                    if (!sessionActive) startSession()
                                    scope.launch { pagerState.animateScrollToPage(1) }
                                }, enabled = true) {
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
                                } else if (state.surfacePressureHpa == null) {
                                    Text("WAITING FOR PRESSURE", color = secondary, fontSize = 11.sp)
                                } else if (isDiving) {
                                    // DIVE FACE — experimental pressure-derived depth
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
                                        text = if (state.stale) "--.-M" else "%.1fM".format(currentDepth),
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
                                            text = currentHeartRate?.let { "$it BPM" } ?: "-- BPM",
                                            color = Color.White,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                } else {
                                    // SURFACE FACE — pressure-derived records
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
                                            Text("DIVE MAX", color = secondary, fontSize = 10.sp, maxLines = 1)
                                            Text("%.1f m".format(lastDiveMax), color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                            Spacer(modifier = Modifier.height(1.dp))
                                            Text("AVG HR", color = secondary, fontSize = 10.sp, maxLines = 1)
                                            Text(
                                                if (lastDiveAverageHeartRate > 0) "$lastDiveAverageHeartRate BPM" else "-- BPM",
                                                color = Color.White,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1
                                            )
                                            Spacer(modifier = Modifier.height(1.dp))
                                            Text("DIVE TIME", color = secondary, fontSize = 10.sp, maxLines = 1)
                                            Text(formatDuration(lastDiveSeconds), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
                                            // SESSION MAX now sits below the interval timer.
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                "SESSION MAX",
                                                color = secondary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                            Text(
                                                "%.1f m".format(sessionMax),
                                                color = accent,
                                                fontSize = 19.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1
                                            )
                                        }
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

                            // PAGE 3 — PRESSURE GRAPH AND LOGGER
                            3 -> {
                                Text("PRESSURE GRAPH • TEST ONLY", color = accent, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1)
                                val delta = if (pressureHpa != null && referencePressureHpa != null)
                                    pressureHpa!! - referencePressureHpa!! else null
                                Text(
                                    when {
                                        !sessionActive -> "START SESSION TO RECORD"
                                        !pressureSensorRunning -> "SENSOR NOT RUNNING"
                                        delta == null -> "WAITING FOR READING"
                                        else -> "LIVE • 1 SAMPLE/SEC"
                                    }, color = secondary, fontSize = 8.sp, maxLines = 1
                                )
                                Text(
                                    "DELTA " + (delta?.let { "%+.2f".format(it) } ?: "--.--") + " hPa",
                                    color = accent, fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1
                                )
                                // Graph auto-scales to the pressure changes observed.
                                PressureHistoryGraph(
                                    samples = pressureHistory.toList(),
                                    lineColor = accent,
                                    zeroColor = secondary,
                                    modifier = Modifier.fillMaxWidth().height(62.dp)
                                )
                                Text(
                                    "DEPTH ~%.2f m".format(((delta ?: 0f) / hpaPerMetre).coerceAtLeast(0f)),
                                    color = secondary, fontSize = 10.sp, maxLines = 1
                                )
                                Text(
                                    "MAX +%.2f hPa    %ds".format(maxPositiveDeltaHpa, recordingSeconds),
                                    color = Color.White, fontSize = 10.sp, maxLines = 1
                                )
                                // SECTION 9A — PRESSURE CONTINUITY (SERVICE SENSOR EVENTS)
                                Text(
                                    "SAMPLES ${state.totalPressureSamples}   GAPS >3s ${state.gapsOver3Seconds}",
                                    color = Color.White, fontSize = 9.sp, maxLines = 1
                                )
                                Text(
                                    "LONGEST GAP %.2fs".format(state.longestSampleGapMs / 1000.0),
                                    color = secondary, fontSize = 9.sp, maxLines = 1
                                )
                                Text(
                                    if (state.lastPressureSampleMs == 0L) "LAST READING --"
                                    else "READING AGE %.1fs".format(state.lastPressureAgeMs / 1000.0),
                                    color = secondary, fontSize = 9.sp, maxLines = 1
                                )
                                if (state.stale) {
                                    Text("PRESSURE DATA STALE", color = Color.Red, fontSize = 9.sp, maxLines = 1)
                                }
                                Button(
                                    onClick = {
                                        state.resetGraph()
                                    },
                                    enabled = sessionActive && pressureHpa != null
                                ) { Text("RESET TEST", fontSize = 10.sp) }
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


// SECTION 11 — LIVE PRESSURE GRAPH (DELTA hPa AGAINST TIME)
@Composable
private fun PressureHistoryGraph(
    samples: List<Float>,
    lineColor: Color,
    zeroColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // Keep zero in the plot; add a small margin so the line never touches the edge.
        val minimum = minOf(0f, samples.minOrNull() ?: 0f)
        val maximum = maxOf(0f, samples.maxOrNull() ?: 0f)
        val range = maxOf(0.2f, maximum - minimum)
        val low = minimum - range * 0.12f
        val high = maximum + range * 0.12f
        fun y(value: Float) = h * (1f - (value - low) / (high - low))

        drawLine(zeroColor.copy(alpha = 0.45f), Offset(0f, y(0f)), Offset(w, y(0f)), 1.dp.toPx())
        if (samples.size >= 2) {
            val path = Path()
            val firstX = 0f
            path.moveTo(firstX, y(samples.first()))
            samples.forEachIndexed { index, value ->
                val x = w * index / (samples.size - 1).toFloat()
                path.lineTo(x, y(value))
            }
            drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
    }
}
