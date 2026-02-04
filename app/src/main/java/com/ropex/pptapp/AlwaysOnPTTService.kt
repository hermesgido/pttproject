package com.ropex.pptapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import androidx.core.app.NotificationCompat
import android.app.Service
import android.os.IBinder
import com.ropex.pptapp.mediasoup.AndroidMediasoupController
import com.ropex.pptapp.signaling.SignalingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AlwaysOnPTTService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var signalingClient: SignalingClient
    private lateinit var mediasoup: AndroidMediasoupController
    private lateinit var httpClient: OkHttpClient
    private lateinit var audioManager: AudioManager

    private var authToken: String? = null
    private var userId: String = ""
    private var userName: String = "User"
    private var deviceId: String? = null
    private var companyId: String? = null

    private var recvTransportId: String? = null
    private var pendingProducerId: String? = null
    private var pendingRecvTransportInfo: JSONObject? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        httpClient = OkHttpClient()
        startForegroundService()

        val prefs = getSharedPreferences("pptapp", Context.MODE_PRIVATE)
        authToken = prefs.getString("authToken", null)
        userId = prefs.getString("userId", "user-${System.currentTimeMillis()}") ?: "user-${System.currentTimeMillis()}"
        userName = prefs.getString("userName", "User") ?: "User"

        signalingClient = SignalingClient(Constants.SERVER_URL, listener)
        mediasoup = AndroidMediasoupController(signalingClient)
        mediasoup.initialize(this)

        scope.launch {
            configureAudioForReceiving()
            signalingClient.connect()
        }
    }

    private fun configureAudioForReceiving() {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setSpeakerphoneOn(true)
            setVoiceCallVolumeFraction(0.9f)
        }
    }

    private fun setVoiceCallVolumeFraction(f: Float) {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val vol = kotlin.math.max(1, kotlin.math.min(maxVolume, (maxVolume * f).toInt()))
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, vol, 0)
        } catch (_: Exception) {}
    }

    private fun startForegroundService() {
        val channelId = "ptt_foreground"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(channelId, "PTT Service", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("PTT Radio")
            .setContentText("Listening for channel audio")
            .setOngoing(true)
            .build()
        startForeground(1001, notif)
    }

    private val listener = object : SignalingClient.SignalingListener {
        override fun onConnected() {
            val t = authToken ?: return
            signalingClient.authConnect(t, userName)
        }

        override fun onAuthOk(deviceId: String, companyId: String) {
            this@AlwaysOnPTTService.deviceId = deviceId
            this@AlwaysOnPTTService.companyId = companyId
            scope.launch { autoJoinMemberChannels(companyId, deviceId) }
        }

        override fun onAuthError(error: String) {}
        override fun onDisconnected(reason: String) {}
        override fun onError(error: String) {}

        override fun onJoinSuccess(channelId: String, userId: String) {
            // After joining, request router RTP caps and create dedicated recv transport
            signalingClient.createTransport("recv")
        }

        override fun onTransportCreated(transportInfo: JSONObject) {
            val direction = transportInfo.optString("direction", "")
            if (recvTransportId == null && (direction == "recv" || direction.isEmpty())) {
                if (!mediasoup.isDeviceLoaded()) {
                    pendingRecvTransportInfo = transportInfo
                    return
                }
                recvTransportId = transportInfo.getString("id")
                mediasoup.createRecvTransport(transportInfo)
                val pid = pendingProducerId
                val caps = mediasoup.getDeviceRtpCapabilities()
                val tId = recvTransportId
                if (pid != null && caps != null && tId != null) {
                    signalingClient.consumeAudio(pid, caps, tId)
                    pendingProducerId = null
                }
            }
        }

        override fun onRtpCapabilities(data: JSONObject) {
            if (!mediasoup.isDeviceLoaded()) {
                mediasoup.loadDevice(data)
            }
            val info = pendingRecvTransportInfo
            if (recvTransportId == null && info != null) {
                recvTransportId = info.getString("id")
                mediasoup.createRecvTransport(info)
                pendingRecvTransportInfo = null
            }
            val pid = pendingProducerId
            val tId = recvTransportId
            val caps = mediasoup.getDeviceRtpCapabilities()
            if (pid != null && caps != null && tId != null) {
                signalingClient.consumeAudio(pid, caps, tId)
                pendingProducerId = null
            }
        }

        override fun onNewProducerInRoom(roomId: String, data: JSONObject) {
            val producerId = data.optString("producerId")
            val caps = mediasoup.getDeviceRtpCapabilities()
            val tId = recvTransportId
            if (producerId.isNotEmpty() && caps != null && tId != null) {
                signalingClient.consumeAudio(producerId, caps, tId)
            } else if (producerId.isNotEmpty()) {
                pendingProducerId = producerId
            }
        }

        override fun onConsumerCreated(data: JSONObject) {
            // Resume playback on incoming audio
            signalingClient.resumeConsumer()
            mediasoup.setConsumersEnabled(true)
        }

        override fun onUserStoppedInRoom(roomId: String, userId: String) {
            // Pause when speech ends
            mediasoup.setConsumersEnabled(false)
        }

        override fun onUsersList(users: JSONArray) {}
        override fun onProducerOk(producerId: String) {}
        override fun onSpeakGranted() {}
        override fun onChannelBusy(currentSpeaker: String) {}
        override fun onUserSpeaking(userId: String, userName: String) {}
        override fun onUserStopped(userId: String) {}
        override fun onUserJoined(userId: String, userName: String) {}
        override fun onUserLeft(userId: String) {}
        override fun onIceCandidate(candidate: JSONObject) {}
        override fun onJoinError(error: String) {}
        override fun onPage(roomId: String, fromUserId: String, fromUserName: String) {
            scope.launch {
                val did = deviceId ?: return@launch
                ensureMembership(roomId, did)
                signalingClient.joinChannel(roomId, userId, userName)
            }
        }
        override fun onNewProducer(data: JSONObject) {}
    }

    private suspend fun autoJoinMemberChannels(companyId: String, deviceId: String) {
        val channels = fetchChannels(companyId)
        channels.forEach { ch ->
            val cid = ch.optString("id")
            ensureMembership(cid, deviceId)
            val prefs = getSharedPreferences("pptapp", Context.MODE_PRIVATE)
            val active = prefs.getString("active_room_id", null)
            if (active == null || active != cid) {
                signalingClient.joinChannel(cid, userId, userName)
            }
        }
    }

    private suspend fun fetchChannels(companyId: String): List<JSONObject> {
        return withContext(Dispatchers.IO) {
            val url = Constants.SERVER_URL + "/v1/companies/" + companyId + "/channels"
            val req = Request.Builder().url(url).get().build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            val out = mutableListOf<JSONObject>()
            var i = 0
            while (i < arr.length()) { out.add(arr.getJSONObject(i)); i++ }
            out
        }
    }

    private suspend fun fetchMembers(channelId: String): List<JSONObject> {
        return withContext(Dispatchers.IO) {
            val url = Constants.SERVER_URL + "/v1/channels/" + channelId + "/members"
            val req = Request.Builder().url(url).get().build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            val out = mutableListOf<JSONObject>()
            var i = 0
            while (i < arr.length()) { out.add(arr.getJSONObject(i)); i++ }
            out
        }
    }

    private suspend fun ensureMembership(channelId: String, deviceId: String) {
        withContext(Dispatchers.IO) {
            val murl = Constants.SERVER_URL + "/v1/channels/" + channelId + "/members"
            val chkReq = Request.Builder().url(murl).get().build()
            val chkResp = httpClient.newCall(chkReq).execute()
            val body = chkResp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            var isMember = false
            var i = 0
            while (i < arr.length()) {
                val d = arr.getJSONObject(i)
                if (d.optString("id") == deviceId) { isMember = true; break }
                i++
            }
            if (!isMember) {
                val payload = JSONObject().apply { put("deviceId", deviceId) }.toString()
                val reqBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val addReq = Request.Builder().url(murl).post(reqBody).build()
                httpClient.newCall(addReq).execute().close()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            mediasoup.setConsumersEnabled(false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
