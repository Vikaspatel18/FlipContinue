package com.example.flipcontinue

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// 1. MAIN SETUP SCREEN (Opens when you tap the app icon on your phone)
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }

        val infoText = TextView(this).apply {
            text = "Z Flip 7 Cover Continue\n\n" +
                    "1. Tap the button below to enable the 'FlipContinue' Accessibility Service.\n" +
                    "2. Make sure your apps are enabled in Good Lock -> MultiStar -> Launcher Widget."
            textSize = 16f
            setPadding(0, 0, 0, 48)
        }

        val btnAccessibility = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(infoText)
        layout.addView(btnAccessibility)
        setContentView(layout)
    }
}

// 2. BACKGROUND TRACKER + HINGE DETECTOR
class FlipTrackerService : AccessibilityService(), SensorEventListener {

    private var lastPackageName: String? = null
    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private var isPhoneOpen = true

    // Ignore system UI, launchers, and our own app
    private val ignoredPackages = setOf(
        "com.example.flipcontinue",
        "com.sec.android.app.launcher",
        "com.android.systemui",
        "com.samsung.android.app.aodservice",
        "com.samsung.android.honeyboard"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
            hingeSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Only record foreground apps while the main screen is open
            if (!isPhoneOpen) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg !in ignoredPackages && packageManager.getLaunchIntentForPackage(pkg) != null) {
                lastPackageName = pkg
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HINGE_ANGLE) {
            val angle = event.values[0]

            // Consider open when hinge angle is greater than 35 degrees
            if (angle > 35f) {
                isPhoneOpen = true
            }
            // Trigger when hinge closes below 5 degrees right after being open
            else if (angle < 5f && isPhoneOpen) {
                isPhoneOpen = false
                lastPackageName?.let { pkg ->
                    showCoverScreenPill(pkg)
                }
            }
        }
    }

    private fun showCoverScreenPill(targetPackage: String) {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // Find Z Flip 7 Cover Screen (Display ID 1, or first non-default display)
        val coverDisplay = displayManager.displays.firstOrNull { it.displayId == 1 }
            ?: displayManager.displays.firstOrNull { it.displayId != 0 }
            ?: return

        // Briefly wake up the screen so you can see the pill on the cover display
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "FlipContinue:CoverWake"
        )
        wakeLock.acquire(5000L)

        // Launch the floating pill Activity directly onto the Cover Display
        val pillIntent = Intent(this, CoverPillActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("TARGET_PACKAGE", targetPackage)
        }

        val options = ActivityOptions.makeBasic().setLaunchDisplayId(coverDisplay.displayId)
        startActivity(pillIntent, options.toBundle())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}

// 3. FLOATING PILL POPUP ON THE COVER SCREEN
class CoverPillActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow the pill to show over the cover screen lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val targetPkg = intent.getStringExtra("TARGET_PACKAGE")
        if (targetPkg == null) {
            finish()
            return
        }

        val pm = packageManager
        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(targetPkg, 0)).toString()
        } catch (e: Exception) {
            "App"
        }
        val appIcon = try {
            pm.getApplicationIcon(targetPkg)
        } catch (e: Exception) {
            null
        }

        // Anchor the transparent window to the bottom of the cover screen
        window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        // Build sleek rounded pill UI
        val pillContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(40, 28, 48, 28)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100f
                setColor(Color.parseColor("#E61E1E1E")) // Dark translucent pill
                setStroke(3, Color.parseColor("#4DFFFFFF"))
            }
            setOnClickListener {
                launchTargetAppOnCover(targetPkg)
            }
        }

        val iconView = ImageView(this).apply {
            setImageDrawable(appIcon)
            layoutParams = LinearLayout.LayoutParams(72, 72).apply {
                marginEnd = 28
            }
        }

        val labelView = TextView(this).apply {
            text = "Continue $appLabel"
            setTextColor(Color.WHITE)
            textSize = 15f
        }

        pillContainer.addView(iconView)
        pillContainer.addView(labelView)

        val outerWrapper = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 16, 32, 48)
            addView(pillContainer)
        }

        setContentView(outerWrapper)

        // Auto-dismiss after 5 seconds if ignored
        handler.postDelayed(autoDismissRunnable, 5000L)
    }

    private fun launchTargetAppOnCover(pkg: String) {
        handler.removeCallbacks(autoDismissRunnable)

        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

        if (launchIntent != null) {
            val currentDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.displayId ?: 1
            } else {
                1
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(currentDisplayId)

            // Prompt fingerprint/unlock if needed, then open app on cover screen
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager.isKeyguardLocked) {
                keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        startActivity(launchIntent, options.toBundle())
                        finish()
                    }
                })
            } else {
                startActivity(launchIntent, options.toBundle())
                finish()
            }
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoDismissRunnable)
    }
}

