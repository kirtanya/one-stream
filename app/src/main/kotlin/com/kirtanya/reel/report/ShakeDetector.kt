package com.kirtanya.reel.report

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.kirtanya.reel.core.report.ShakeGate
import kotlin.math.sqrt

/**
 * A firm double shake of the phone ([ShakeGate]) → [onShake], on the main thread. Listens only
 * between [start] and [stop] (while one of our activities is on screen).
 */
internal class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gate = ShakeGate()
    private var listening = false

    fun start() {
        if (listening || accelerometer == null) return
        // GAME rate (~50 Hz): a jolt lasts well under 100 ms, slower rates miss its peak.
        listening = sensors?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME) == true
    }

    fun stop() {
        if (!listening) return
        sensors?.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (gate.feed(g, event.timestamp / 1_000_000)) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
