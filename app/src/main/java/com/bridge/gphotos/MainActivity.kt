package com.bridge.gphotos

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var counterText: TextView
    private lateinit var toggleBtn: Button

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                100
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "Pixel XL Live Photo Bridge"
            textSize = 24f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 16)
        }

        ipText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#8AB4F8"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }

        counterText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#BDC1C6"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }

        toggleBtn = Button(this).apply {
            textSize = 18f
            setPadding(48, 36, 48, 36)
            setOnClickListener {
                val serviceIntent = Intent(this@MainActivity, ReceiverService::class.java)
                if (ReceiverService.isRunning) {
                    stopService(serviceIntent)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                }
            }
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(ipText)
        layout.addView(counterText)
        layout.addView(toggleBtn)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(ReceiverService.ACTION_UPDATE))
        // Auto-start server as soon as she opens the app!
        if (!ReceiverService.isRunning && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            val serviceIntent = Intent(this, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        }
        updateUI()
    }

    override fun onPause() {
        unregisterReceiver(receiver)
        super.onPause()
    }

    private fun getWifiIp(): String {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        return if (ip == "0.0.0.0") "Check Wi-Fi Connection" else "http://$ip:5000"
    }

    private fun updateUI() {
        val running = ReceiverService.isRunning
        statusText.text = if (running) "● RECEIVER READY" else "○ RECEIVER STOPPED"
        statusText.setTextColor(if (running) Color.parseColor("#81C995") else Color.parseColor("#F28B82"))
        ipText.text = getWifiIp()
        counterText.text = "Files received this session: ${ReceiverService.savedCount}\nLast saved: ${ReceiverService.lastFile}\nTarget: /DCIM/GPhotos"
        toggleBtn.text = if (running) "Stop Receiver" else "Start Receiver"
    }
}