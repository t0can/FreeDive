package com.FreeDive.app.presentation

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.compose.runtime.*
import java.util.Locale
import kotlin.math.ceil

// SECTION S1 — SHARED STATE (SURVIVES ACTIVITY RECREATION WHILE PROCESS LIVES)
data class DiveRecord(val durationSeconds: Int, val maximumDepthMetres: Float, val averageHeartRate: Int)
object DiveState {
    var sessionActive by mutableStateOf(false)
    var sessionEnded by mutableStateOf(false)
    var sessionStartMs by mutableLongStateOf(0L)
    var sessionSeconds by mutableIntStateOf(0)
    var isDiving by mutableStateOf(false)
    var currentDepth by mutableFloatStateOf(0f)
    var currentHeartRate by mutableStateOf<Int?>(null)
    var diveStartMs by mutableLongStateOf(0L)
    var diveElapsedSeconds by mutableIntStateOf(0)
    var currentDiveMax by mutableFloatStateOf(0f)
    var lastDiveMax by mutableFloatStateOf(0f)
    var sessionMax by mutableFloatStateOf(0f)
    var lastDiveSeconds by mutableIntStateOf(0)
    var lastDiveAverageHeartRate by mutableIntStateOf(0)
    var heartRateTotal by mutableIntStateOf(0)
    var heartRateSamples by mutableIntStateOf(0)
    var intervalRemainingSeconds by mutableIntStateOf(0)
    var intervalEndMs by mutableLongStateOf(0L)
    var intervalActive by mutableStateOf(false)
    var intervalComplete by mutableStateOf(false)
    val completedDives = mutableStateListOf<DiveRecord>()
    var surfacePressureHpa by mutableStateOf<Float?>(null)
    var pressureHpa by mutableStateOf<Float?>(null)
    var referencePressureHpa by mutableStateOf<Float?>(null)
    var pressureSensorRunning by mutableStateOf(false)
    var lastPressureSampleMs by mutableLongStateOf(0L)
    var stale by mutableStateOf(true)
    var maxPositiveDeltaHpa by mutableFloatStateOf(0f)
    var recordingSeconds by mutableIntStateOf(0)
    val pressureHistory = mutableStateListOf<Float>()
    var lastSampleGapMs by mutableLongStateOf(0L)
    // SECTION S1A — SENSOR CONTINUITY DIAGNOSTICS
    var totalPressureSamples by mutableLongStateOf(0L)
    var longestSampleGapMs by mutableLongStateOf(0L)
    var gapsOver3Seconds by mutableIntStateOf(0)
    var lastPressureAgeMs by mutableLongStateOf(0L)
    const val HPA_PER_METRE = 100.55f // approximate seawater
    fun resetGraph() {
        referencePressureHpa = pressureHpa
        pressureHistory.clear()
        maxPositiveDeltaHpa = 0f
        recordingSeconds = 0
    }
}

// SECTION S2 — FOREGROUND SENSOR SERVICE
class DiveSessionService : Service(), SensorEventListener {
    companion object {
        const val ACTION_START = "com.FreeDive.app.START"
        const val ACTION_STOP = "com.FreeDive.app.STOP"
        private const val CHANNEL_ID = "freedive_recording"
        private const val NOTIFICATION_ID = 1801
    }
    private lateinit var sensors: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private var pressureRegistered = false
    private var heartRegistered = false
    private var lastGraphTick = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    // SECTION S3 — TIMED RECORDING / FRESHNESS
    private val ticker = object : Runnable {
        override fun run() {
            if (!DiveState.sessionActive) return
            val now = SystemClock.elapsedRealtime()
            val s = DiveState
            s.sessionSeconds = ((now - s.sessionStartMs) / 1000).toInt()
            if (s.isDiving) s.diveElapsedSeconds = ((now - s.diveStartMs) / 1000).toInt()
            s.lastPressureAgeMs = if (s.lastPressureSampleMs == 0L) 0L
            else (now - s.lastPressureSampleMs).coerceAtLeast(0L)
            s.stale = s.lastPressureSampleMs == 0L || s.lastPressureAgeMs > 3000L
            if (s.intervalActive) {
                s.intervalRemainingSeconds = ceil((s.intervalEndMs - now).coerceAtLeast(0L) / 1000.0).toInt()
                if (s.intervalRemainingSeconds == 0) {
                    s.intervalActive = false
                    s.intervalComplete = true
                    vibrate()
                }
            }
            if (now - lastGraphTick >= 1000L) {
                // No fabricated data points during gaps.
                if (!s.stale) {
                    val p = s.pressureHpa
                    val ref = s.referencePressureHpa
                    if (p != null && ref != null) {
                        val delta = p - ref
                        s.pressureHistory.add(delta)
                        if (s.pressureHistory.size > 300) s.pressureHistory.removeAt(0)
                        s.maxPositiveDeltaHpa = maxOf(s.maxPositiveDeltaHpa, delta)
                        s.recordingSeconds++
                    }
                }
                lastGraphTick = now
            }
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val channel = NotificationChannel(CHANNEL_ID, "FreeDive recording", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // SECTION S4 — SERVICE ACTIONS
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!DiveState.sessionActive) {
                    val sensor = sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)
                    if (sensor == null) { stopSelf(); return START_NOT_STICKY }
                    // SECTION S4A — WEAR OS ONGOING ACTIVITY
                    // Tapping the ongoing-activity indicator returns to FreeDive.
                    // It does not force the app over Samsung's Water Lock screen.
                    val returnToApp = PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setContentTitle("FreeDive recording")
                        .setContentText("Session active · experimental pressure depth")
                        .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setOngoing(true)
                        .setContentIntent(returnToApp)

                    // apply() must run BEFORE the notification is built and posted.
                    OngoingActivity.Builder(this, NOTIFICATION_ID, notificationBuilder)
                        .setStaticIcon(android.R.drawable.ic_menu_compass)
                        .setTouchIntent(returnToApp)
                        .build()
                        .apply(this)

                    val notification = notificationBuilder.build()
                    if (Build.VERSION.SDK_INT >= 34) {
                        startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                    } else startForeground(NOTIFICATION_ID, notification)
                    val s = DiveState
                    s.completedDives.clear()
                    s.sessionStartMs = SystemClock.elapsedRealtime()
                    s.sessionSeconds = 0
                    s.isDiving = false
                    s.currentDepth = 0f
                    s.currentHeartRate = null
                    s.diveStartMs = 0L
                    s.diveElapsedSeconds = 0
                    s.currentDiveMax = 0f
                    s.lastDiveMax = 0f
                    s.sessionMax = 0f
                    s.lastDiveSeconds = 0
                    s.lastDiveAverageHeartRate = 0
                    s.heartRateTotal = 0
                    s.heartRateSamples = 0
                    s.intervalRemainingSeconds = 0
                    s.intervalEndMs = 0L
                    s.intervalActive = false
                    s.intervalComplete = false
                    s.sessionEnded = false
                    s.surfacePressureHpa = null // first fresh reading sets baseline
                    s.pressureHpa = null
                    s.lastPressureSampleMs = 0L
                    s.stale = true
                    s.lastSampleGapMs = 0L
                    s.totalPressureSamples = 0L
                    s.longestSampleGapMs = 0L
                    s.gapsOver3Seconds = 0
                    s.lastPressureAgeMs = 0L
                    s.resetGraph()
                    s.sessionActive = true
                    pressureRegistered = sensors.registerListener(this, sensor, 200_000)
                    s.pressureSensorRunning = pressureRegistered
                    val hrPermission = if (Build.VERSION.SDK_INT >= 36)
                        "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
                    if (ContextCompat.checkSelfPermission(this, hrPermission) == PackageManager.PERMISSION_GRANTED) {
                        sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let {
                            heartRegistered = sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                        }
                    }
                    // CPU wake lock, NOT a screen wake lock; released on stop.
                    val power = getSystemService(POWER_SERVICE) as PowerManager
                    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FreeDive:Recording").apply { acquire() }
                    lastGraphTick = 0L
                    handler.post(ticker)
                }
            }
            ACTION_STOP -> {
                if (!DiveState.isDiving) {
                    DiveState.sessionSeconds = ((SystemClock.elapsedRealtime() - DiveState.sessionStartMs) / 1000).toInt()
                    DiveState.sessionActive = false
                    DiveState.sessionEnded = true
                    DiveState.intervalActive = false
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY // do not silently resume with a fabricated baseline after process death
    }

    // SECTION S5 — SENSOR EVENTS / EXPERIMENTAL DEPTH
    override fun onSensorChanged(event: SensorEvent) {
        val s = DiveState
        if (!s.sessionActive) return
        when (event.sensor.type) {
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values.firstOrNull() ?: return
                if (!pressure.isFinite() || pressure <= 0f) return
                val now = SystemClock.elapsedRealtime()
                if (s.lastPressureSampleMs != 0L) {
                    val gap = (now - s.lastPressureSampleMs).coerceAtLeast(0L)
                    s.lastSampleGapMs = gap
                    s.longestSampleGapMs = maxOf(s.longestSampleGapMs, gap)
                    if (gap > 3000L) s.gapsOver3Seconds++
                }
                s.totalPressureSamples++
                s.lastPressureAgeMs = 0L
                s.lastPressureSampleMs = now
                s.stale = false
                s.pressureHpa = pressure
                if (s.surfacePressureHpa == null) s.surfacePressureHpa = pressure
                if (s.referencePressureHpa == null) s.referencePressureHpa = pressure
                val baseline = s.surfacePressureHpa ?: return
                val depth = ((pressure - baseline) / DiveState.HPA_PER_METRE).coerceAtLeast(0f)
                s.currentDepth = depth
                updateDive(depth, now)
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values.firstOrNull()?.toInt() ?: return
                if (bpm in 30..240) {
                    s.currentHeartRate = bpm
                    if (s.isDiving) { s.heartRateTotal += bpm; s.heartRateSamples++ }
                }
            }
        }
    }

    private fun updateDive(depth: Float, now: Long) {
        val s = DiveState
        if (!s.isDiving && depth > 1f) {
            s.isDiving = true
            s.currentDiveMax = depth
            s.diveStartMs = now
            s.diveElapsedSeconds = 0
            s.heartRateTotal = 0
            s.heartRateSamples = 0
            s.intervalActive = false
            s.intervalComplete = false
            vibrate()
        }
        if (s.isDiving) {
            s.currentDiveMax = maxOf(s.currentDiveMax, depth)
            s.sessionMax = maxOf(s.sessionMax, depth)
            if (depth <= 1f) {
                s.lastDiveMax = s.currentDiveMax
                s.lastDiveSeconds = ((now - s.diveStartMs) / 1000).toInt()
                s.lastDiveAverageHeartRate = if (s.heartRateSamples > 0) s.heartRateTotal / s.heartRateSamples else 0
                s.completedDives.add(DiveRecord(s.lastDiveSeconds, s.lastDiveMax, s.lastDiveAverageHeartRate))
                val recovery = s.lastDiveSeconds * 2
                s.intervalRemainingSeconds = recovery
                s.intervalEndMs = now + recovery * 1000L
                s.intervalActive = recovery > 0
                s.intervalComplete = recovery == 0
                s.isDiving = false
                vibrate()
            }
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun vibrate() {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    }
    override fun onBind(intent: Intent?) = null
    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        DiveState.sessionActive = false
        sensors.unregisterListener(this)
        pressureRegistered = false
        heartRegistered = false
        DiveState.pressureSensorRunning = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
