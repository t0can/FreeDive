
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.FreeDive.app.presentation.theme.FreeDiveTheme
import kotlinx.coroutines.delay
import kotlin.math.ceil
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.wear.compose.material3.Icon

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FreeDiveScreen()
        }
    }
}

@Composable
fun FreeDiveScreen() {
    val background = Color(0xFF07151C)
    val accent = Color(0xFF63D8D2)
    val secondary = Color(0xFFA9C6CE)
    val context = LocalContext.current

    // Session timer.
    val sessionStartMs = remember {
        SystemClock.elapsedRealtime()
    }
    var sessionSeconds by remember { mutableStateOf(0) }

    // Current dive state.
    var isDiving by remember { mutableStateOf(false) }
    var currentDepth by remember { mutableStateOf(0f) }
    var currentHeartRate by remember { mutableStateOf(70) }
    var diveElapsedSeconds by remember { mutableStateOf(0) }
    var diveStartMs by remember { mutableStateOf(0L) }

    // Maximum depths.
    var currentDiveMax by remember { mutableStateOf(0f) }
    var lastDiveMax by remember { mutableStateOf(0f) }
    var sessionMax by remember { mutableStateOf(0f) }

    // Previous dive statistics.
    var lastDiveSeconds by remember { mutableStateOf(0) }
    var lastDiveAverageHeartRate by remember {
        mutableStateOf(0)
    }

    // Heart-rate samples collected during the current dive.
    var heartRateTotal by remember { mutableStateOf(0) }
    var heartRateSamples by remember { mutableStateOf(0) }

    // Surface interval countdown.
    var intervalRemainingSeconds by remember { mutableStateOf(0) }
    var intervalEndMs by remember { mutableStateOf(0L) }
    var intervalActive by remember { mutableStateOf(false) }
    var intervalComplete by remember { mutableStateOf(false) }

    fun vibrateWatch() {
        val vibrator = context.getSystemService(Vibrator::class.java)

        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    200L,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        }
    }

    // Process a depth and heart-rate reading.
    fun updateReading(depth: Float, heartRate: Int) {
        val now = SystemClock.elapsedRealtime()

        currentDepth = depth.coerceAtLeast(0f)
        currentHeartRate = heartRate

        // Start a new dive when depth exceeds 1 metre.
        if (!isDiving && currentDepth > 1.0f) {
            isDiving = true
            currentDiveMax = currentDepth
            diveStartMs = now

            heartRateTotal = 0
            heartRateSamples = 0

            intervalActive = false
            intervalComplete = false

            vibrateWatch()
        }

        if (isDiving) {
            currentDiveMax = maxOf(currentDiveMax, currentDepth)
            sessionMax = maxOf(sessionMax, currentDepth)

            // Record heart rate while underwater.
            if (currentDepth > 1.0f) {
                heartRateTotal += heartRate
                heartRateSamples++
            }

            // End the dive when returning to 1 metre or shallower.
            if (currentDepth <= 1.0f) {
                lastDiveMax = currentDiveMax

                lastDiveSeconds =
                    ((now - diveStartMs) / 1000L).toInt()

                lastDiveAverageHeartRate =
                    if (heartRateSamples > 0) {
                        heartRateTotal / heartRateSamples
                    } else {
                        0
                    }

                // Recovery reminder: twice the dive duration.
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

    // SIMULATED FEED — replace with real sensor readings later.
    LaunchedEffect(Unit) {
        val simulatedReadings = listOf(
            0.0f to 72,
            0.3f to 73,
            0.7f to 74,
            1.2f to 76,
            2.5f to 80,
            4.0f to 84,
            7.5f to 89,
            10.0f to 92,
            12.4f to 96,
            11.0f to 94,
            8.0f to 90,
            5.0f to 86,
            2.0f to 82,
            1.2f to 80,
            0.8f to 78
        )

        while (true) {
            for ((depth, heartRate) in simulatedReadings) {
                updateReading(depth, heartRate)
                delay(1500L)
            }

            // Surface pause for testing the countdown.
            updateReading(0.0f, 72)
            delay(40_000L)
        }
    }

    // Update elapsed timers and surface countdown.
    LaunchedEffect(Unit) {
        while (true) {
            val now = SystemClock.elapsedRealtime()

            sessionSeconds =
                ((now - sessionStartMs) / 1000L).toInt()

            if (isDiving) {
                diveElapsedSeconds =
                    ((now - diveStartMs) / 1000L).toInt()
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

    FreeDiveTheme {
        AppScaffold {
            ScreenScaffold { contentPadding ->

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(background)
                        .padding(contentPadding)
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {

                    if (isDiving) {
                        // DIVE FACE — compact layout

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

                            // Current depth, with unit included.
                            Text(
                                text = "%.1fM".format(currentDepth),
                                color = accent,
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            // Heart icon and heart-rate value.
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

                                Spacer(modifier = Modifier.padding(horizontal = 3.dp))

                                Text(
                                    text = "$currentHeartRate BPM",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        } else {
                        // SURFACE FACE
                        // ---------------------------------

                        // Small session duration at the top.
                        Text(
                            text = formatDuration(sessionSeconds),
                            color = secondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        // Left: previous dive statistics.
                        // Right: main surface interval countdown.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "DIVE MAX",
                                    color = secondary,
                                    fontSize = 7.sp,
                                    maxLines = 1
                                )

                                Text(
                                    text = "%.1f m".format(lastDiveMax),
                                    color = accent,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "AVG HR",
                                    color = secondary,
                                    fontSize = 7.sp,
                                    maxLines = 1
                                )

                                Text(
                                    text = if (lastDiveAverageHeartRate > 0) {
                                        "$lastDiveAverageHeartRate BPM"
                                    } else {
                                        "-- BPM"
                                    },
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "DIVE TIME",
                                    color = secondary,
                                    fontSize = 7.sp,
                                    maxLines = 1
                                )

                                Text(
                                    text = formatDuration(lastDiveSeconds),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1.2f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = when {
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
                                    text = when {
                                        intervalActive ->
                                            formatDuration(
                                                intervalRemainingSeconds
                                            )
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
                                    text = when {
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

                        // Session maximum beneath the two columns.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "SESSION MAX",
                                color = secondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )

                            Text(
                                text = "%.1f m".format(sessionMax),
                                color = accent,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatDuration(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    return "%02d:%02d".format(minutes, seconds)
}
