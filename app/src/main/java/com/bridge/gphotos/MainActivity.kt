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
    private lateinit var codeHeader: TextView
    private lateinit var codeDisplay: TextView
    private lateinit var urlSubtext: TextView
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
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Pixel XL GPhotos Bridge"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        codeHeader = TextView(this).apply {
            text = "CONNECTION CODE"
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        codeDisplay = TextView(this).apply {
            textSize = 56f
            setTextColor(Color.parseColor("#8AB4F8"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        urlSubtext = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#9AA0A6"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        counterText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#BDC1C6"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        toggleBtn = Button(this).apply {
            textSize = 16f
            setPadding(48, 32, 48, 32)
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
        layout.addView(codeHeader)
        layout.addView(codeDisplay)
        layout.addView(urlSubtext)
        layout.addView(counterText)
        layout.addView(toggleBtn)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(ReceiverService.ACTION_UPDATE))
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

    private fun getWifiDetails(): Triple<String, String, String> {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        if (ip == "0.0.0.0") return Triple("---", "No Wi-Fi Connection", "")
        val lastOctet = ip.substringAfterLast(".")
        return Triple(lastOctet, "http://pixelbridge.local:5000", "IP: $ip:5000")
    }

    private fun updateUI() {
        val running = ReceiverService.isRunning
        val (code, mdnsUrl, fullIp) = getWifiDetails()
        statusText.text = if (running) "● READY TO RECEIVE" else "○ RECEIVER STOPPED"
        statusText.setTextColor(if (running) Color.parseColor("#81C995") else Color.parseColor("#F28B82"))
        
        codeDisplay.text = code
        urlSubtext.text = "$mdnsUrl\n$fullIp"
        counterText.text = "Saved this session: ${ReceiverService.savedCount}\nLast: ${ReceiverService.lastFile}\nPath: /DCIM/GPhotos"
        toggleBtn.text = if (running) "Stop Receiver" else "Start Receiver"
    }
}
