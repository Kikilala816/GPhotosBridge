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
                // Fallback: Read until EOF if content-length is missing or 0
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
