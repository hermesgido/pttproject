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
        fun onAuthOk(deviceId: String, companyId: String)
        fun onAuthError(error: String)
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
        fun onProducerOk(producerId: String)
        fun onPage(roomId: String, fromUserId: String, fromUserName: String)

      // Room-aware callbacks (optional)
      fun onUserSpeakingInRoom(roomId: String, userId: String, userName: String) {}
      fun onUserStoppedInRoom(roomId: String, userId: String) {}
      fun onNewProducerInRoom(roomId: String, data: JSONObject) {}
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

    fun authConnect(token: String, userName: String) {
        val data = JSONObject().apply {
            put("token", token)
            put("userName", userName)
        }
        socket?.emit("auth:connect", data)
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

            on("auth:ok") { args ->
                val data = args[0] as JSONObject
                val deviceId = data.getString("deviceId")
                val companyId = data.getString("companyId")
                listener.onAuthOk(deviceId, companyId)
            }

            on("auth:error") { args ->
                val data = args[0] as JSONObject
                val error = data.optString("error", "auth error")
                listener.onAuthError(error)
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
                val roomId = data.optString("roomId", "")
                listener.onUserSpeaking(userId, userName)
                if (roomId.isNotEmpty()) listener.onUserSpeakingInRoom(roomId, userId, userName)
            }

            on("user-stopped") { args ->
                val data = args[0] as JSONObject
                val userId = data.getString("userId")
                val roomId = data.optString("roomId", "")
                listener.onUserStopped(userId)
                if (roomId.isNotEmpty()) listener.onUserStoppedInRoom(roomId, userId)
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
                val roomId = data.optString("roomId", "")
                listener.onNewProducer(data)
                if (roomId.isNotEmpty()) listener.onNewProducerInRoom(roomId, data)
            }

            on("consumer-created") { args ->
                val data = args[0] as JSONObject
                listener.onConsumerCreated(data)
            }

            on("producer-ok") { args ->
                val data = args[0] as JSONObject
                val pid = data.optString("producerId")
                if (pid.isNotEmpty()) listener.onProducerOk(pid)
            }

            on("page") { args ->
                val data = args[0] as JSONObject
                val roomId = data.optString("roomId", "")
                val fromUserId = data.optString("fromUserId", "")
                val fromUserName = data.optString("fromUserName", "")
                if (roomId.isNotEmpty()) listener.onPage(roomId, fromUserId, fromUserName)
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

  fun produceAudioSync(transportId: String, rtpParameters: JSONObject, timeoutMs: Long = 2000): String {
    val data = JSONObject().apply {
      put("transportId", transportId)
      put("kind", "audio")
      put("rtpParameters", rtpParameters)
    }
    var outId: String = ""
    try {
      val latch = java.util.concurrent.CountDownLatch(1)
      socket?.emit("produce-audio", data, io.socket.client.Ack { args ->
        try {
          if (args != null && args.isNotEmpty()) {
            val obj = args[0] as? JSONObject
            val pid = obj?.optString("producerId") ?: ""
            if (pid.isNotEmpty()) outId = pid
          }
        } catch (_: Throwable) {}
        latch.countDown()
      })
      latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: Throwable) {}
    return outId
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

    fun pageUser(toDeviceId: String, roomId: String, fromUserId: String, fromUserName: String) {
        val data = JSONObject().apply {
            put("toDeviceId", toDeviceId)
            put("roomId", roomId)
            put("fromUserId", fromUserId)
            put("fromUserName", fromUserName)
        }
        socket?.emit("page", data)
    }
}
