package com.ropex.pptapp.signaling

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener
) {
    private var socket: Socket? = null

    interface SignalingListener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onError(error: String)
        fun onJoinSuccess(channelId: String, userId: String)
        fun onJoinError(error: String)
        fun onSpeakGranted()
        fun onChannelBusy(currentSpeaker: String)
        fun onUserSpeaking(userId: String, userName: String)
        fun onUserStopped(userId: String)
        fun onUserJoined(userId: String, userName: String)
        fun onUserLeft(userId: String)
        fun onIceCandidate(candidate: JSONObject)
        fun onTransportCreated(transportInfo: JSONObject)
    }

    fun connect() {
        try {
            val options = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 1000
                timeout = 20000
            }

            socket = IO.socket(serverUrl, options)
            setupEventListeners()
            socket?.connect()

        } catch (e: URISyntaxException) {
            listener.onError("Invalid server URL: ${e.message}")
        }
    }

    private fun setupEventListeners() {
        socket?.apply {
            on(Socket.EVENT_CONNECT) {
                Log.d("Signaling", "Connected to server")
                listener.onConnected()
            }

            on(Socket.EVENT_DISCONNECT) {
                Log.d("Signaling", "Disconnected from server")
                listener.onDisconnected("Disconnected")
            }

            on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = args[0]?.toString() ?: "Unknown error"
                Log.e("Signaling", "Connection error: $error")
                listener.onError("Connection error: $error")
            }

            // Add these event handlers
            on("join-success") { args ->
                val data = args[0] as JSONObject
                val channelId = data.getString("roomId")
                val userId = data.getString("userId")
                listener.onJoinSuccess(channelId, userId)
            }

            on("speak-granted") {
                listener.onSpeakGranted()
            }

            on("channel-busy") { args ->
                val data = args[0] as JSONObject
                val currentSpeaker = data.getString("currentSpeaker")
                listener.onChannelBusy(currentSpeaker)
            }

            on("user-speaking") { args ->
                val data = args[0] as JSONObject
                val userId = data.getString("userId")
                val userName = data.getString("userName")
                listener.onUserSpeaking(userId, userName)
            }

            on("user-stopped") { args ->
                val data = args[0] as JSONObject
                val userId = data.getString("userId")
                listener.onUserStopped(userId)
            }
        }
    }

    fun joinChannel(channelId: String, userId: String, userName: String) {
        val data = JSONObject().apply {
            put("roomId", channelId)
            put("userId", userId)
            put("userName", userName)
        }
        socket?.emit("join-room", data)
    }

    fun requestSpeak(channelId: String, userId: String) {
        val data = JSONObject().apply {
            put("roomId", channelId)
            put("userId", userId)
        }
        socket?.emit("request-speak", data)
    }

    fun stopSpeaking(channelId: String, userId: String) {
        val data = JSONObject().apply {
            put("roomId", channelId)
            put("userId", userId)
        }
        socket?.emit("stop-speaking", data)
    }

    fun leaveChannel(channelId: String, userId: String) {
        val data = JSONObject().apply {
            put("roomId", channelId)
            put("userId", userId)
        }
        socket?.emit("leave-room", data)
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
    }

    fun isConnected(): Boolean {
        return socket?.connected() == true
    }
}