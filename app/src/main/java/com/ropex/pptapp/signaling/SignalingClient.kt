package com.ropex.pptapp.signaling

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.json.JSONArray
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
        fun onRtpCapabilities(data: JSONObject)
        fun onNewProducer(data: JSONObject)
        fun onConsumerCreated(data: JSONObject)
        fun onUsersList(users: JSONArray)
    }

    fun connect() {
        try {
            val options = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 1000
                timeout = 20000
                transports = arrayOf("websocket")
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

            on("user-joined") { args ->
                val data = args[0] as JSONObject
                val userId = data.getString("userId")
                val userName = data.getString("userName")
                listener.onUserJoined(userId, userName)
            }

            on("user-left") { args ->
                val data = args[0] as JSONObject
                val userId = data.getString("userId")
                listener.onUserLeft(userId)
            }

            on("users") { args ->
                val data = args[0] as JSONObject
                val users = data.optJSONArray("users") ?: JSONArray()
                listener.onUsersList(users)
            }

            on("transport-created") { args ->
                val data = args[0] as JSONObject
                listener.onTransportCreated(data)
            }

            on("rtp-capabilities") { args ->
                val data = args[0] as JSONObject
                listener.onRtpCapabilities(data)
            }

            on("new-producer") { args ->
                val data = args[0] as JSONObject
                listener.onNewProducer(data)
            }

            on("consumer-created") { args ->
                val data = args[0] as JSONObject
                listener.onConsumerCreated(data)
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

    fun requestUsers(channelId: String) {
        val data = JSONObject().apply {
            put("roomId", channelId)
        }
        socket?.emit("get-users", data)
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
    }

    fun isConnected(): Boolean {
        return socket?.connected() == true
    }

    fun createTransport(direction: String) {
        val data = JSONObject().apply {
            put("direction", direction)
        }
        socket?.emit("create-transport", data)
    }

    fun connectTransport(transportId: String, dtlsParameters: JSONObject) {
        val data = JSONObject().apply {
            put("transportId", transportId)
            put("dtlsParameters", dtlsParameters)
        }
        socket?.emit("connect-transport", data)
    }

    fun produceAudio(transportId: String, rtpParameters: JSONObject) {
        val data = JSONObject().apply {
            put("transportId", transportId)
            put("kind", "audio")
            put("rtpParameters", rtpParameters)
        }
        socket?.emit("produce-audio", data)
    }

    fun consumeAudio(producerId: String, rtpCapabilities: JSONObject, transportId: String?) {
        val data = JSONObject().apply {
            put("producerId", producerId)
            put("rtpCapabilities", rtpCapabilities)
            if (transportId != null) put("transportId", transportId)
        }
        socket?.emit("consume-audio", data)
    }

    fun resumeConsumer() {
        socket?.emit("resume-consumer")
    }

    fun pauseConsumer() {
        socket?.emit("pause-consumer")
    }
}
