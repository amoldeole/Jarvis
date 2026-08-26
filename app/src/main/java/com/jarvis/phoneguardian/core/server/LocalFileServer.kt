package com.jarvis.phoneguardian.core.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jarvis.phoneguardian.R
import com.jarvis.phoneguardian.core.database.AppDatabase
import com.jarvis.phoneguardian.core.security.SecureTokenStore
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object LocalServerRegistry {
    @Volatile var running: Boolean = false
    @Volatile var address: String? = null
    @Volatile var token: String? = null
}

/**
 * Minimal authenticated LAN server. It binds only while the user explicitly enables it,
 * rejects non-private peers, expires by revocation, and never forwards ports to the internet.
 */
class LocalFileServerService : Service() {
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private lateinit var tokenStore: SecureTokenStore
    private lateinit var database: AppDatabase

    override fun onCreate() {
        super.onCreate()
        tokenStore = SecureTokenStore(this)
        database = AppDatabase.get(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        if (serverThread?.isAlive != true) startServer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        val token = tokenStore.getOrCreateServerToken()
        serverThread = Thread {
            try {
                ServerSocket(0, 8, InetAddress.getByName("0.0.0.0")).use { socket ->
                    serverSocket = socket
                    val address = "http://${lanAddress()}:${socket.localPort}"
                    LocalServerRegistry.running = true
                    LocalServerRegistry.address = address
                    LocalServerRegistry.token = token
                    while (!socket.isClosed) {
                        runCatching { socket.accept() }.getOrNull()?.let { client ->
                            Thread { handle(client, token) }.start()
                        }
                    }
                }
            } catch (_: Throwable) {
                // The UI will show the server as stopped; no exception is exposed to users.
            } finally {
                LocalServerRegistry.running = false
                LocalServerRegistry.address = null
                LocalServerRegistry.token = null
            }
        }.apply { name = "guardian-local-server"; start() }
    }

    private fun stopServer() {
        serverSocket?.close()
        serverThread?.interrupt()
        tokenStore.revokeServerToken()
        LocalServerRegistry.running = false
        LocalServerRegistry.address = null
        LocalServerRegistry.token = null
    }

    private fun handle(socket: Socket, token: String) {
        socket.use { client ->
            client.soTimeout = 15_000
            if (!isPrivatePeer(client.inetAddress)) return
            val input = BufferedInputStream(client.getInputStream())
            val request = readAsciiLine(input) ?: return
            val parts = request.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                line.substringBefore(':', "").trim().lowercase().takeIf { it.isNotBlank() }?.let { key ->
                    headers[key] = line.substringAfter(':').trim()
                }
            }
            if (!isAuthorized(target, headers, token)) {
                respond(client.getOutputStream(), 401, "text/plain", "Pair this device again to access Phone Guardian.")
                return
            }
            val (path, query) = target.substringBefore('?') to parseQuery(target.substringAfter('?', ""))
            when {
                method == "GET" && path == "/" -> respond(client.getOutputStream(), 200, "text/html; charset=utf-8", homePage(token))
                method == "GET" && path == "/download" -> download(client.getOutputStream(), query["uri"])
                method == "PUT" && path == "/upload" -> upload(client.getOutputStream(), input, headers["content-length"]?.toLongOrNull() ?: 0L, query["name"])
                else -> respond(client.getOutputStream(), 404, "text/plain", "Not found")
            }
        }
    }

    private fun download(output: OutputStream, encodedUri: String?) {
        val uri = encodedUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri == null) {
            respond(output, 400, "text/plain", "Missing file")
            return
        }
        val name = runBlocking { database.fileDao().find(uri.toString())?.fileName } ?: "download"
        val stream = contentResolver.openInputStream(uri)
        if (stream == null) {
            respond(output, 404, "text/plain", "File is no longer available")
            return
        }
        val size = runBlocking { database.fileDao().find(uri.toString())?.size } ?: -1L
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"${escapeHeader(name)}\"\r\n${if (size >= 0) "Content-Length: $size\r\n" else ""}\r\n"
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        stream.use { it.copyTo(output) }
        output.flush()
    }

    private fun upload(output: OutputStream, input: BufferedInputStream, length: Long, name: String?) {
        if (length <= 0 || length > MAX_UPLOAD_BYTES) {
            respond(output, 413, "text/plain", "Upload must be between 1 byte and 2 GB.")
            return
        }
        val safeName = (name ?: "uploaded-file").replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
        // Uploads land in an app-private inbox; a user must explicitly move them to shared storage.
        val destination = File(filesDir, "inbox").apply { mkdirs() }.resolve(safeName)
        try {
            destination.outputStream().use { outputStream ->
                var remaining = length
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) error("The upload connection ended early.")
                    outputStream.write(buffer, 0, read)
                    remaining -= read
                }
            }
            respond(output, 201, "text/plain", "Uploaded to Phone Guardian inbox. Open the app to review and move it to shared storage.")
        } catch (error: Throwable) {
            destination.delete()
            respond(output, 400, "text/plain", "Upload failed safely: ${error.message ?: "connection error"}")
        }
    }

    private fun homePage(token: String): String {
        val files = runBlocking { database.fileDao().getRecent(500) }
        val rows = files.joinToString("\n") { file ->
            val encoded = java.net.URLEncoder.encode(file.uri, "UTF-8")
            "<li><b>${escapeHtml(file.fileName)}</b> <small>${file.size / 1024} KB · ${escapeHtml(file.mediaType)}</small> <a href=\"/download?uri=$encoded&amp;token=$token\">Download</a><br><small>${escapeHtml(file.displayPath)}</small></li>"
        }
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>Phone Guardian</title><style>body{font:16px system-ui;max-width:900px;margin:2rem auto;padding:0 1rem;color:#20202b}li{padding:1rem 0;border-bottom:1px solid #ddd}a{color:#4b4bc3;margin-left:1rem}</style></head><body><h1>Phone Guardian</h1><p>Paired local browser access · ${files.size} indexed files</p><p><input id="upload" type="file"><button onclick="send()">Upload to inbox</button></p><p id="status"></p><ul>$rows</ul><script>async function send(){const f=document.getElementById('upload').files[0];if(!f)return;document.getElementById('status').textContent='Uploading…';const t=new URLSearchParams(location.search).get('token');const r=await fetch('/upload?token='+encodeURIComponent(t)+'&name='+encodeURIComponent(f.name),{method:'PUT',body:f});document.getElementById('status').textContent=await r.text();}</script></body></html>"""
    }

    private fun respond(output: OutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"; else -> "Error" }
        output.write("HTTP/1.1 $status $reason\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
    }

    private fun isAuthorized(target: String, headers: Map<String, String>, token: String): Boolean {
        val queryToken = parseQuery(target.substringAfter('?', ""))["token"]
        return queryToken == token || headers["authorization"] == "Bearer $token"
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else String(bytes.toByteArray(), StandardCharsets.US_ASCII)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
            if (bytes.size > 16_384) return null
        }
        return String(bytes.toByteArray(), StandardCharsets.US_ASCII)
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull {
        val key = it.substringBefore('=', "")
        val value = it.substringAfter('=', "")
        if (key.isBlank()) null else URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }.toMap()

    private fun isPrivatePeer(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        return false
    }

    private fun lanAddress(): String = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        Collections.list(interfaces).flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull() ?: "127.0.0.1"

    private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun escapeHeader(value: String): String = value.replace(Regex("[\\\"\r\n]"), "_")

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Local browser access", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Phone Guardian browser access")
        .setContentText("Only paired devices on your local network can connect").setOngoing(true).build()

    companion object {
        const val ACTION_START = "com.jarvis.phoneguardian.START_SERVER"
        const val ACTION_STOP = "com.jarvis.phoneguardian.STOP_SERVER"
        private const val CHANNEL_ID = "local_server"
        private const val NOTIFICATION_ID = 42
        private const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024
    }
}
