/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.signin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.text.TextUtils
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.metrolist.wear.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

sealed interface SignInState {
    data object Connecting : SignInState

    /** The watch couldn't get onto Wi-Fi, so nothing on the network can reach it. */
    data object NoWifi : SignInState

    data class Ready(
        val url: String,
        val qr: ImageBitmap,
    ) : SignInState

    data object Done : SignInState
}

/**
 * Serves a one-page form on the watch's Wi-Fi address so any browser on the same network can hand
 * over a YouTube Music cookie. The page lives under a random code that the QR carries, and the
 * server only runs while the sign-in screen is open.
 */
class SignInServer(
    private val context: Context,
    private val onCookie: (String) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()

    private val _state = MutableStateFlow<SignInState>(SignInState.Connecting)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var serverJob: Job? = null
    private var socket: ServerSocket? = null
    private var address: InetAddress? = null

    @Volatile
    private var code = newCode()
    private var misses = 0

    fun start() {
        _state.value = SignInState.Connecting
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val ipv4 = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }
                    if (ipv4 == null) {
                        _state.value = SignInState.NoWifi
                    } else if (ipv4 != address) {
                        listen(ipv4)
                    }
                }

                override fun onLost(network: Network) = closeSocket()

                override fun onUnavailable() {
                    _state.value = SignInState.NoWifi
                }
            }
        networkCallback = callback
        // Wear OS keeps Wi-Fi off while Bluetooth to the phone works; requesting it turns it on.
        connectivity.requestNetwork(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            callback,
            WIFI_TIMEOUT_MS,
        )
    }

    fun stop() {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
        closeSocket()
        scope.cancel()
    }

    private fun closeSocket() {
        serverJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        address = null
        if (_state.value !is SignInState.Done) _state.value = SignInState.NoWifi
    }

    private fun listen(ip: InetAddress) {
        closeSocket()
        address = ip
        serverJob =
            scope.launch {
                val server =
                    runCatching { ServerSocket(PREFERRED_PORT, BACKLOG, ip) }.recoverCatching { ServerSocket(0, BACKLOG, ip) }.getOrElse {
                        Timber.w(it, "Sign-in server failed to start")
                        _state.value = SignInState.NoWifi
                        return@launch
                    }
                socket = server
                publishUrl()
                while (isActive) {
                    val client = runCatching { server.accept() }.getOrElse { break }
                    runCatching { client.use { handle(it) } }.onFailure { Timber.d(it, "Sign-in request failed") }
                    if (_state.value is SignInState.Done) break
                }
                runCatching { server.close() }
            }
    }

    private fun publishUrl() {
        val server = socket ?: return
        val host = address?.hostAddress ?: return
        val port = if (server.localPort == 80) "" else ":${server.localPort}"
        val url = "http://$host$port/$code"
        _state.value = SignInState.Ready(url, qrCode(url))
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        val input = client.getInputStream().buffered()
        val requestLine = input.readHttpLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')

        var contentLength = 0
        while (true) {
            val header = input.readHttpLine() ?: return
            if (header.isEmpty()) break
            if (header.startsWith("content-length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val out = client.getOutputStream()

        if (path != "/$code") {
            // Codes are 6 digits; after this many misses, move the page somewhere new.
            if (++misses >= MAX_MISSES) {
                misses = 0
                code = newCode()
                publishUrl()
            }
            out.respond("404 Not Found", page(context.getString(R.string.signin_page_expired), form = false))
            return
        }
        when (method) {
            "GET" -> out.respond("200 OK", page(null, form = true))
            "POST" -> {
                if (contentLength !in 1..MAX_BODY_BYTES) {
                    out.respond("413 Content Too Large", page(context.getString(R.string.signin_page_invalid), form = true))
                    return
                }
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(body, read, contentLength - read)
                    if (n < 0) return
                    read += n
                }
                val cookie =
                    formField(String(body, Charsets.UTF_8), "cookie")?.let(CookieImport::parse) ?: run {
                        out.respond("400 Bad Request", page(context.getString(R.string.signin_page_invalid), form = true))
                        return
                    }
                withContext(Dispatchers.Main) { onCookie(cookie) }
                out.respond("200 OK", page(context.getString(R.string.signin_page_done), form = false))
                _state.value = SignInState.Done
            }
            else -> out.respond("405 Method Not Allowed", "")
        }
    }

    private fun formField(
        body: String,
        name: String,
    ): String? =
        body
            .split('&')
            .firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun page(
        message: String?,
        form: Boolean,
    ): String {
        fun s(id: Int) = TextUtils.htmlEncode(context.getString(id))
        val formHtml =
            if (!form) {
                ""
            } else {
                """
                <p>${s(R.string.signin_page_hint)}</p>
                <form method="post">
                  <textarea id="c" name="cookie" rows="8" placeholder="SAPISID=…; __Secure-3PSID=…" required></textarea>
                  <label>${s(R.string.signin_page_file)} <input type="file" id="f" accept=".txt,.json"></label>
                  <button type="submit">${s(R.string.signin_page_submit)}</button>
                </form>
                <script>
                  document.getElementById('f').onchange = e => {
                    const r = new FileReader();
                    r.onload = () => { document.getElementById('c').value = r.result; };
                    r.readAsText(e.target.files[0]);
                  };
                </script>
                """.trimIndent()
            }
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Metrolist</title>
            <style>
              :root { color-scheme: light dark; }
              body { font-family: system-ui, sans-serif; max-width: 36rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; }
              textarea { width: 100%; box-sizing: border-box; font-family: monospace; font-size: .85rem; }
              label, button { display: block; margin-top: 1rem; }
              button { font-size: 1rem; padding: .6rem 1.4rem; }
              .msg { font-weight: 600; }
            </style></head>
            <body><h1>${s(R.string.signin_page_title)}</h1>
            ${message?.let { "<p class=\"msg\">${TextUtils.htmlEncode(it)}</p>" }.orEmpty()}
            $formHtml
            </body></html>
            """.trimIndent()
    }

    private fun newCode() = (100_000 + random.nextInt(900_000)).toString()

    private companion object {
        const val PREFERRED_PORT = 8080
        const val BACKLOG = 4
        const val WIFI_TIMEOUT_MS = 20_000
        const val SOCKET_TIMEOUT_MS = 15_000
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_MISSES = 20
        const val QR_SIZE_PX = 256

        fun qrCode(text: String): ImageBitmap {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, mapOf(EncodeHintType.MARGIN to 1))
            val bitmap = createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
            for (x in 0 until matrix.width) {
                for (y in 0 until matrix.height) {
                    bitmap[x, y] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            return bitmap.asImageBitmap()
        }

        fun InputStream.readHttpLine(): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val b = read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) break
                if (bytes.size() >= MAX_LINE_BYTES) return null
                bytes.write(b)
            }
            return bytes.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
        }

        fun OutputStream.respond(
            status: String,
            html: String,
        ) {
            val body = html.toByteArray(Charsets.UTF_8)
            val head =
                "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }
}
