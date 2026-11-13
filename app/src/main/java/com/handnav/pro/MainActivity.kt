package com.handnav.pro

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvPermCamera: TextView
    private lateinit var tvPermAccessibility: TextView
    private lateinit var tvPermNotif: TextView
    private lateinit var btnPermissions: MaterialButton

    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            ui.postDelayed(this, 250L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvPermCamera = findViewById(R.id.tvPermCamera)
        tvPermAccessibility = findViewById(R.id.tvPermAccessibility)
        tvPermNotif = findViewById(R.id.tvPermNotif)
        btnPermissions = findViewById(R.id.btnPermissions)

        btnPermissions.setOnClickListener { requestRuntimePermissions() }
        findViewById<MaterialButton>(R.id.btnAccessibility).setOnClickListener { openAccessibilitySettings() }
        findViewById<MaterialButton>(R.id.btnStart).setOnClickListener { onStartClicked() }
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            TrackingService.stop(this)
            toast("Navigation stopped")
        }
        findViewById<MaterialButton>(R.id.btnCalibrate).setOnClickListener {
            val svc = TrackingService.instance
            if (svc == null) toast("Start navigation first, then calibrate")
            else svc.startCalibration()
        }
        findViewById<MaterialButton>(R.id.btnResetCalib).setOnClickListener {
            settings.centerX = 0.5f
            settings.centerY = 0.5f
            toast("Calibration reset")
        }

        bindSwitch(R.id.swPointer, settings.pointerVisible) { settings.pointerVisible = it }
        bindSwitch(R.id.swInvert, settings.invertScroll) { settings.invertScroll = it }
        bindSwitch(R.id.swGpu, settings.useGpu) { settings.useGpu = it }

        bindSlider(R.id.slSensitivity, settings.sensitivity) { settings.sensitivity = it }
        bindSlider(R.id.slSpeed, settings.speed) { settings.speed = it }
        bindSlider(R.id.slSmoothing, settings.smoothing) { settings.smoothing = it }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    // ---------------- Controls ----------------

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<SwitchCompat>(id)
        sw.isChecked = initial
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
    }

    private fun bindSlider(id: Int, initial: Int, onChange: (Int) -> Unit) {
        val s = findViewById<Slider>(id)
        s.value = initial.coerceIn(0, 100).toFloat()
        s.addOnChangeListener { _, value, fromUser -> if (fromUser) onChange(value.toInt()) }
    }

    private fun onStartClicked() {
        if (!hasCamera()) {
            toast("Camera permission is needed first")
            requestRuntimePermissions()
            return
        }
        if (!isAccessibilityEnabled()) {
            toast("Enable the Accessibility Service for Hand Navigation PRO first")
            openAccessibilitySettings()
            return
        }
        TrackingState.error = null
        TrackingService.start(this)
        toast("Starting... the pointer appears when a hand is visible")
    }

    // ---------------- Permissions ----------------

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasNotifications() =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePermissions() {
        val list = ArrayList<String>()
        if (!hasCamera()) list.add(Manifest.permission.CAMERA)
        if (!hasNotifications()) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (list.isEmpty()) {
            toast("Camera and notification permissions are already granted")
            return
        }
        permissionLauncher.launch(list.toTypedArray())
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (NavAccessibilityService.instance != null) return true
        val cn = ComponentName(this, NavAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            toast("Open Settings > Accessibility manually")
        }
    }

    // ---------------- Status ----------------

    private fun refreshStatus() {
        val cam = hasCamera()
        val a11y = isAccessibilityEnabled()
        val notif = hasNotifications()
        tvPermCamera.text = (if (cam) "[OK] " else "[MISSING] ") + "Camera permission"
        tvPermAccessibility.text = (if (a11y) "[OK] " else "[MISSING] ") + "Accessibility Service (enable manually)"
        tvPermNotif.text = if (Build.VERSION.SDK_INT < 33) "[OK] Notifications (not needed on this Android version)"
        else (if (notif) "[OK] " else "[MISSING] ") + "Notification permission (so the camera notification and Stop button show)"
        btnPermissions.isEnabled = !cam || !notif

        val sb = StringBuilder()
        sb.append("Navigation : ").append(if (TrackingState.running) "RUNNING" else "STOPPED").append('\n')
        sb.append("Hand       : ").append(if (TrackingState.handVisible) "detected" else "not detected").append('\n')
        sb.append("Gesture    : ").append(GestureEngine.LABELS[TrackingState.gesture.coerceIn(0, 5)])
        if (TrackingState.paused) sb.append("  (PAUSED - show open palm to resume)")
        sb.append('\n')
        sb.append("Speed      : ").append(String.format("%.0f fps, %.0f ms/frame, %s", TrackingState.fps, TrackingState.inferenceMs, TrackingState.delegate)).append('\n')
        sb.append("Debug      : ").append(
            String.format(
                "idx %.2f mid %.2f ring %.2f pinky %.2f pinch %.2f",
                TrackingState.idxR, TrackingState.midR, TrackingState.ringR, TrackingState.pnkR, TrackingState.pinchR
            )
        )
        TrackingState.error?.let { sb.append("\nERROR      : ").append(it) }
        tvStatus.text = sb.toString()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
