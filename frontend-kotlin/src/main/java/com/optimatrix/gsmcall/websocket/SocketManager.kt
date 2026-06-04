package com.optimatrix.gsmcall.websocket

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class SocketManager(private val hostUrl: String) {
    private val client = OkHttpClient()
    private var socket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(hostUrl).build()
        socket = client.newWebSocket(request, socketListener)
        client.dispatcher.executorService.shutdown()
    }

    fun sendEvent(event: String) {
        socket?.send(event)
    }

    fun close() {
        socket?.close(1000, "Client closed")
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            Log.d("SocketManager", "WebSocket connected to $hostUrl")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d("SocketManager", "Message received: $text")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            Log.d("SocketManager", "WebSocket failure: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("SocketManager", "WebSocket closed: $code $reason")
        }
    }
}
