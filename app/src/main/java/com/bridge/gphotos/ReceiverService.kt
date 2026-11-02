package com.bridge.gphotos

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class ReceiverService : Service() {
    private var server: BridgeServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var jmdns: JmDNS? = null

    companion object {
        var isRunning = false
        var savedCount = 0
        var lastFile = "None yet"
        const val ACTION_UPDATE = "com.bridge.gphotos.UPDATE"
    }

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GPhotosBridge::WakeLock")
        wakeLock?.acquire()

        startForegroundServiceNotification()
        server = BridgeServer(applicationContext, 5000)
        server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        startMdnsBroadcast()

        isRunning = true
        broadcastUpdate()
    }

    private fun startMdnsBroadcast() {
        Thread {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wm.createMulticastLock("GPhotosBridge::Multicast").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                val ipInt = wm.connectionInfo.ipAddress
                if (ipInt != 0) {
                    val ipBytes = byteArrayOf(
                        (ipInt and 0xff).toByte(),
                        (ipInt shr 8 and 0xff).toByte(),
                        (ipInt shr 16 and 0xff).toByte(),
                        (ipInt shr 24 and 0xff).toByte()
                    )
                    val addr = InetAddress.getByAddress(ipBytes)
                    jmdns = JmDNS.create(addr, "pixelbridge")
                    val serviceInfo = ServiceInfo.create("_http._tcp.local.", "PixelBridge", 5000, "GPhotos Bridge")
                    jmdns?.registerService(serviceInfo)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onDestroy() {
        Thread {
            try {
                jmdns?.unregisterAllServices()
                jmdns?.close()
                if (multicastLock?.isHeld == true) multicastLock?.release()
            } catch (e: Exception) {}
        }.start()
        server?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        isRunning = false
        broadcastUpdate()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastUpdate() {
        sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName))
    }

    private fun startForegroundServiceNotification() {
        val channelId = "gphotos_bridge_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Live Photo Receiver",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }
            .setContentTitle("GPhotos Bridge Active")
            .setContentText("Listening on port 5000 for iPhone Live Photos...")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .build()

        startForeground(1, notification)
    }

    inner class BridgeServer(private val ctx: Context, port: Int) : NanoHTTPD(port) {
        private val pendingJpegs = ConcurrentHashMap<String, ByteArray>()
        private val saveDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "GPhotos"
        ).apply { if (!exists()) mkdirs() }

        override fun serve(session: IHTTPSession): Response {
            if (session.method == Method.GET && session.uri == "/ping") {
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "PONG")
            }
            if (session.method != Method.POST || session.uri != "/upload") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Ready")
            }

            return try {
                val headers = session.headers
                val partType = headers["x-part"] ?: "normal"
                val rawName = headers["x-filename"] ?: "IMG_${System.currentTimeMillis()}.jpg"
                val baseName = rawName.substringBeforeLast(".")
                val ext = rawName.substringAfterLast(".", "jpg")
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

                val bodyBytes = readExactBytes(session.inputStream, contentLength)

                when (partType) {
                    "live-image" -> {
                        if (bodyBytes.isEmpty()) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Empty image body for $baseName")
                        }
                        pendingJpegs[baseName] = bodyBytes
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Cached JPEG for $baseName (${bodyBytes.size} bytes)")
                    }
                    "live-video" -> {
                        val imgBytes = pendingJpegs.remove(baseName)
                            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing JPEG cache for $baseName")

                        if (bodyBytes.isEmpty()) {
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Empty video body for $baseName")
                        }

                        val outFile = File(saveDir, "${baseName}_MP.jpg")
                        val xmpSegment = createMotionPhotoXmp(bodyBytes.size)

                        FileOutputStream(outFile).use { fos ->
                            fos.write(imgBytes, 0, 2)
                            fos.write(xmpSegment)
                            fos.write(imgBytes, 2, imgBytes.size - 2)
                            fos.write(bodyBytes)
                        }

                        scanFile(outFile)
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Saved Motion Photo: ${outFile.name}")
                    }
                    else -> {
                        val outFile = File(saveDir, "$baseName.$ext")
                        FileOutputStream(outFile).use { it.write(bodyBytes) }
                        scanFile(outFile)
                        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Saved Media: ${outFile.name} (${bodyBytes.size} bytes)")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
            }
        }

        private fun readExactBytes(input: java.io.InputStream, length: Int): ByteArray {
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(16384)
            if (length > 0) {
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(data, 0, minOf(data.size, remaining))
                    if (read == -1) break
                    buffer.write(data, 0, read)
                    remaining -= read
                }
            } else {
                var read: Int
                while (true) {
                    read = input.read(data)
                    if (read == -1) break
                    buffer.write(data, 0, read)
                }
            }
            return buffer.toByteArray()
        }

        private fun createMotionPhotoXmp(videoLength: Int): ByteArray {
            val xmpStr = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/">
      <GCamera:MicroVideo>1</GCamera:MicroVideo>
      <GCamera:MicroVideoVersion>1</GCamera:MicroVideoVersion>
      <GCamera:MicroVideoPresentationTimestampUs>1500000</GCamera:MicroVideoPresentationTimestampUs>
      <GCamera:MicroVideoOffset>$videoLength</GCamera:MicroVideoOffset>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
            val xmpBytes = xmpStr.toByteArray(Charsets.UTF_8)
            val namespace = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8)
            val segLen = 2 + namespace.size + xmpBytes.size
            val header = byteArrayOf(
                0xFF.toByte(), 0xE1.toByte(),
                ((segLen shr 8) and 0xFF).toByte(), (segLen and 0xFF).toByte()
            )
            return header + namespace + xmpBytes
        }

        private fun scanFile(file: File) {
            savedCount++
            lastFile = file.name
            broadcastUpdate()
            MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
        }
    }
}
