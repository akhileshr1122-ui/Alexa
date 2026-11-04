package com.haloapps.halobot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private lateinit var face: FaceView
    private lateinit var preview: PreviewView
    private var robot: Robot? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startRobot() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this)
        face = FaceView(this)
        root.addView(face, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            alpha = 0f
        }
        val d = resources.displayMetrics.density
        root.addView(preview, FrameLayout.LayoutParams((140 * d).toInt(), (105 * d).toInt(), Gravity.END or Gravity.BOTTOM).apply {
            setMargins(0, 0, (12 * d).toInt(), (12 * d).toInt())
        })
        setContentView(root)
        hideBars()
        setupGestures()

        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (need.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startRobot()
        else perms.launch(need)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startRobot() {
        val r = robot ?: Robot(this, face, preview).also { robot = it }
        r.start(granted(Manifest.permission.CAMERA), granted(Manifest.permission.RECORD_AUDIO))
    }

    fun setPreviewVisible(v: Boolean) { preview.alpha = if (v) .9f else 0f }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupGestures() {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { robot?.onTap(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { robot?.onDoubleTap(); return true }
            override fun onLongPress(e: MotionEvent) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                robot?.onPetMove(abs(distanceX) + abs(distanceY), face.width)
                return true
            }
        })
        face.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        robot?.let { it.applyPrefs(); it.resume() }
    }

    override fun onPause() {
        super.onPause()
        robot?.pause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onDestroy() {
        robot?.destroy()
        super.onDestroy()
    }
}
