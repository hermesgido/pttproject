package com.ropex.pptapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ropex.pptapp.signaling.SignalingClient
import com.ropex.pptapp.ui.theme.PPTAPPTheme
import com.ropex.pptapp.webrtc.WebRTCManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import com.ropex.pptapp.Constants
import com.ropex.pptapp.mediasoup.MediasoupController
import com.ropex.pptapp.mediasoup.AndroidMediasoupController
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    // State
    private var isTransmitting by mutableStateOf(false)
    private var currentSpeaker by mutableStateOf<String?>(null)
    private var hasPermission by mutableStateOf(false)
    private var isConnectedToServer by mutableStateOf(false)
    private var isAuthenticated by mutableStateOf(false)
    private var roomId by mutableStateOf("")
    private var userId by mutableStateOf("user-${System.currentTimeMillis()}")
    private var userName by mutableStateOf("User ${(1..100).random()}")
    private var authToken: String? = null
    private var companyIdInput by mutableStateOf("")
    private var accountNumberInput by mutableStateOf("")
    private var passwordInput by mutableStateOf("")
    private var isLoggingIn by mutableStateOf(false)
    private var authErrorMessage by mutableStateOf<String?>(null)
    private var rtpCapabilitiesJson: JSONObject? = null
    private var sendTransportId: String? = null
    private var recvTransportId: String? = null
    private var currentProducerId: String? = null
    private var dtlsParametersJson: JSONObject? = null
    private var rtpParametersJson: JSONObject? = null
    private var pendingSendTransportInfo: JSONObject? = null
    private var pendingRecvTransportInfo: JSONObject? = null
    private val connectedUsers = mutableSetOf<String>()
    private var connectedUsersCount by mutableStateOf(0)
    private var volumeDownPressStart: Long = 0L
    private var wasTransmittingBeforePress: Boolean = false
    private val longPressThresholdMs: Long = 350

    // Managers
    private lateinit var audioManager: AudioManager
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRTCManager: WebRTCManager
    private lateinit var mediasoupController: MediasoupController
    private lateinit var androidMediasoupController: AndroidMediasoupController
    private lateinit var httpClient: OkHttpClient
    private var deviceId: String? = null
    private var companyId: String? = null
    private var selectedTabIndex by mutableStateOf(0)
    private var showSettingsMenu by mutableStateOf(false)
    private var contacts = mutableStateListOf<DeviceItem>()
    private var channels = mutableStateListOf<ChannelItem>()
    private var presenceMap = mutableStateMapOf<String, PresenceInfo>()
    private var recents = mutableStateListOf<RecentItem>()
    private var activeSessionName by mutableStateOf<String?>(null)
    private var activeSessionType by mutableStateOf<String?>(null)

    // Coroutine scope for UI updates
    private val uiScope = CoroutineScope(Dispatchers.Main)

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasPermission = isGranted
        if (isGranted) {
            Log.d("PTT", "Microphone permission granted")
            setupAudio()
            initializeWebRTC()
            showToast("Microphone permission granted!")
        } else {
            Log.w("PTT", "Microphone permission denied")
            showToast("Microphone permission is required for PTT!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize managers
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // Create signaling client
        signalingClient = SignalingClient(
            serverUrl = Constants.SERVER_URL,
            listener = signalingListener
        )

        // Create WebRTC manager
        webRTCManager = WebRTCManager(
            context = this,
            signalingListener = webRTCListener
        )

        mediasoupController = MediasoupController()
        mediasoupController.setCallback { dtls, rtp ->
            dtlsParametersJson = dtls
            rtpParametersJson = rtp
        }

        androidMediasoupController = AndroidMediasoupController(signalingClient)
        androidMediasoupController.initialize(this)

        httpClient = OkHttpClient()

        runCatching {
            val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
            authToken = prefs.getString("authToken", null)
            val savedUserId = prefs.getString("userId", null)
            val savedUserName = prefs.getString("userName", null)
            if (!savedUserId.isNullOrEmpty()) userId = savedUserId
            if (!savedUserName.isNullOrEmpty()) userName = savedUserName
        }

        Log.d("PTT", "MainActivity created")

        // Check initial permission
        hasPermission = checkMicrophonePermission()

        // Set UI content
        setContent {
            PPTAPPTheme {
                if (!isAuthenticated) {
                    LoginScreen(
                        companyId = companyIdInput,
                        accountNumber = accountNumberInput,
                        password = passwordInput,
                        isConnected = isConnectedToServer,
                        isLoggingIn = isLoggingIn,
                        errorMessage = authErrorMessage,
                        onCompanyIdChange = { companyIdInput = it },
                        onAccountNumberChange = { accountNumberInput = it },
                        onPasswordChange = { passwordInput = it },
                        onLogin = { attemptLogin(companyIdInput, accountNumberInput, passwordInput) }
                    )
                } else if (roomId.isEmpty()) {
                    MainTabbedScreen(
                        selectedTabIndex = selectedTabIndex,
                        onSelectTab = { selectedTabIndex = it },
                        accountDisplayName = userName,
                        contacts = contacts,
                        channels = channels,
                        presenceMap = presenceMap,
                        recents = recents,
                        showSettingsMenu = showSettingsMenu,
                        onToggleSettingsMenu = { showSettingsMenu = !showSettingsMenu },
                        onLogout = { performLogout() },
                        onToggleSpeaker = { toggleSpeaker() },
                        onContactClick = { device ->
                            uiScope.launch { startDirectContact(device.id, device.displayName) }
                        },
                        onChannelClick = { channel ->
                            uiScope.launch { startChannelTalk(channel.id, channel.name) }
                        },
                        onRecentClick = { item ->
                            uiScope.launch {
                                if (item.type == "contact") {
                                    startDirectContact(item.refId, item.name)
                                } else {
                                    startChannelTalk(item.refId, item.name)
                                }
                            }
                        },
                        onDeleteRecent = { item -> removeRecent(item) }
                    )
                } else {
                    TalkScreen(
                        title = activeSessionName ?: "",
                        isTransmitting = isTransmitting,
                        currentSpeaker = currentSpeaker,
                        hasPermission = hasPermission,
                        isConnectedToServer = isConnectedToServer,
                        onBack = { endSession() },
                        onPTTPressed = {
                            if (hasPermission && isConnectedToServer && roomId.isNotEmpty()) {
                                uiScope.launch {
                                    isTransmitting = true
                                    currentSpeaker = "You"
                                    startTransmitting()
                                    signalingClient.requestSpeak(roomId, userId)
                                }
                            } else if (!hasPermission) {
                                requestAudioPermission()
                            }
                        },
                        onPTTReleased = {
                            uiScope.launch {
                                if (isTransmitting) {
                                    isTransmitting = false
                                    currentSpeaker = null
                                    stopTransmitting()
                                    signalingClient.stopSpeaking(roomId, userId)
                                }
                            }
                        }
                    )
                }
            }
        }

        // Initialize if permission already granted
        if (hasPermission) {
            setupAudio()
            initializeWebRTC()
        } else {
            requestAudioPermission()
        }
    }

    private fun initializeWebRTC() {
        // Initialize WebRTC
        webRTCManager.initialize()

        // Create ICE servers list
        val iceServers = Constants.WebRTC.ICE_SERVERS.map { serverUrl ->
            PeerConnection.IceServer.builder(serverUrl).createIceServer()
        }

        // Create peer connection
        webRTCManager.createPeerConnection(iceServers)

        // Create local audio track
        webRTCManager.createLocalAudioTrack()

        // Connect to signaling server
        signalingClient.connect()
    }

    private fun attemptLogin(companyId: String, accountNumber: String, password: String) {
        uiScope.launch {
            try {
                isLoggingIn = true
                val url = Constants.SERVER_URL + "/v1/auth/login"
                val json = JSONObject().apply {
                    put("companyId", companyId)
                    put("accountNumber", accountNumber)
                    put("password", password)
                }.toString()
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).build()
                val response = withContext(Dispatchers.IO) { httpClient.newCall(request).execute() }
                val code = response.code
                val bodyStr = response.body?.string() ?: ""
                if (code != 200) {
                    var errMsg = ""
                    try { errMsg = JSONObject(bodyStr).optString("error", "") } catch (_: Exception) {}
                    Log.e("Auth", "Login failed code=$code body=$bodyStr")
                    authErrorMessage = if (errMsg.isNotEmpty()) errMsg else "HTTP $code"
                    showToast(if (errMsg.isNotEmpty()) "Login failed: $errMsg" else "Login failed ($code)")
                    return@launch
                }
                val resp = JSONObject(bodyStr)
                val token = resp.optString("token")
                val deviceObj = resp.optJSONObject("device")
                if (token.isNotEmpty() && deviceObj != null) {
                    authToken = token
                    userName = deviceObj.optString("displayName", userName)
                    userId = deviceObj.optString("id", userId)
                    runCatching {
                        val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
                        prefs.edit()
                            .putString("authToken", authToken)
                            .putString("userId", userId)
                            .putString("userName", userName)
                            .apply()
                    }
                    authErrorMessage = null
                    signalingClient.authConnect(token, userName)
                } else {
                    authErrorMessage = "Invalid login response"
                    showToast("Invalid login response")
                }
            } catch (e: Exception) {
                showToast("Login error")
                Log.e("Auth", "Login error", e)
            } finally {
                isLoggingIn = false
            }
        }
    }

    private fun checkMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        Log.d("PTT", "Requesting microphone permission")
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun setupAudio() {
        try {
            // Configure audio for voice communication
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setSpeakerphoneOn(true)

            setVoiceCallVolumeFraction(0.8f)

            Log.d("PTT", "Audio setup complete: mode=MODE_IN_COMMUNICATION, speakerphone=ON")

        } catch (e: Exception) {
            Log.e("PTT", "Audio setup failed", e)
        }
    }

    private fun startTransmitting() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setSpeakerphoneOn(false)
        } catch (_: Exception) {}
        androidMediasoupController.setConsumersEnabled(false)
        signalingClient.pauseConsumer()
        // webRTCManager.setNoiseGate(
        //     Constants.WebRTC.USE_NOISE_GATE,
        //     Constants.WebRTC.NOISE_GATE_RMS_THRESHOLD,
        //     Constants.WebRTC.NOISE_GATE_ATTACK_MS,
        //     Constants.WebRTC.NOISE_GATE_RELEASE_MS
        // )
        webRTCManager.setTransmitState(true)
        try {
            Log.d("PTT", "Started transmitting")
        } catch (e: Exception) {
            Log.e("PTT", "Start transmitting failed", e)
        }
    }

    private fun stopTransmitting() {
        webRTCManager.setTransmitState(false)

        // Change audio mode back
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setSpeakerphoneOn(true)
            setVoiceCallVolumeFraction(0.9f)
            Log.d("PTT", "Stopped transmitting")
        } catch (e: Exception) {
            Log.e("PTT", "Stop transmitting failed", e)
        }
        androidMediasoupController.setConsumersEnabled(true)
        signalingClient.resumeConsumer()
    }

    private fun setVoiceCallVolumeFraction(f: Float) {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val vol = kotlin.math.max(1, kotlin.math.min(maxVolume, (maxVolume * f).toInt()))
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, vol, 0)
            Log.d("PTT", "VOICE_CALL volume set: $vol/$maxVolume")
        } catch (_: Exception) {}
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Signaling Listener
    private val signalingListener = object : SignalingClient.SignalingListener {
        override fun onConnected() {
            uiScope.launch {
                isConnectedToServer = true
                showToast("Connected to server!")
                Log.d("Signaling", "Connected to server")
                authToken?.let { signalingClient.authConnect(it, userName) }
            }
        }

        override fun onAuthOk(deviceId: String, companyId: String) {
            uiScope.launch {
                this@MainActivity.deviceId = deviceId
                this@MainActivity.companyId = companyId
                isAuthenticated = true
                showToast("Authenticated")
                refreshData()
            }
        }

        override fun onAuthError(error: String) {
            uiScope.launch {
                isAuthenticated = false
                showToast("Auth error")
            }
        }

        override fun onDisconnected(reason: String) {
            uiScope.launch {
                isConnectedToServer = false
                showToast("Disconnected: $reason")
                Log.d("Signaling", "Disconnected: $reason")
            }
        }

        override fun onError(error: String) {
            Log.e("Signaling", error)
        }

        override fun onJoinSuccess(channelId: String, userId: String) {
            Log.d("Signaling", "Joined channel $channelId as $userId")
            // Add local audio track after joining
            webRTCManager.addLocalAudioTrack()
            signalingClient.createTransport("send")
            signalingClient.createTransport("recv")
            mediasoupController.prepareProducer(Constants.WebRTC.AUDIO_TRACK_ID)
            uiScope.launch {
                connectedUsers.clear()
                connectedUsers.add(userId)
                connectedUsersCount = connectedUsers.size
            }
            signalingClient.requestUsers(channelId)
        }

        override fun onJoinError(error: String) {
            showToast("Join failed: $error")
        }

        override fun onSpeakGranted() {
            // Server granted permission to speak
            uiScope.launch {
                Log.d("PTT", "Speak granted, starting transmitting and producing audio")
                isTransmitting = true
                currentSpeaker = "You"
                startTransmitting()
                webRTCManager.getLocalAudioTrack()?.let { track ->
                    Log.d("PTT", "Local audio track ready, calling mediasoup produce")
                    androidMediasoupController.produceAudio(track)
                }
            }
        }

        override fun onChannelBusy(currentSpeaker: String) {
            showToast("Channel busy: $currentSpeaker")
        }

        override fun onUserSpeaking(userId: String, userName: String) {
            uiScope.launch {
                currentSpeaker = "$userName ($userId)"
            }
        }

        override fun onUserStopped(userId: String) {
            uiScope.launch {
                currentSpeaker = null
            }
        }

        override fun onUserJoined(userId: String, userName: String) {
            Log.d("Signaling", "User joined: $userName ($userId)")
            uiScope.launch {
                connectedUsers.add(userId)
                connectedUsersCount = connectedUsers.size
            }
        }

        override fun onUserLeft(userId: String) {
            Log.d("Signaling", "User left: $userId")
            uiScope.launch {
                connectedUsers.remove(userId)
                connectedUsersCount = connectedUsers.size
            }
        }

        override fun onUsersList(users: JSONArray) {
            uiScope.launch {
                connectedUsers.clear()
                var i = 0
                while (i < users.length()) {
                    val u = users.getJSONObject(i)
                    val uid = u.optString("userId")
                    if (uid.isNotEmpty()) connectedUsers.add(uid)
                    i++
                }
                connectedUsersCount = connectedUsers.size
            }
        }

        override fun onIceCandidate(candidate: JSONObject) {
            // Handle ICE candidate from server
            // TODO: Implement when we add proper WebRTC signaling
        }

        override fun onTransportCreated(transportInfo: JSONObject) {
            val id = transportInfo.getString("id")
            val direction = transportInfo.optString("direction", "")
            Log.d("Mediasoup", "Transport created dir=\"$direction\" id=$id")
            if (direction == "send" && sendTransportId == null) {
                sendTransportId = id
            } else if (direction == "recv" && recvTransportId == null) {
                recvTransportId = id
            }

            val iceParameters = transportInfo.optJSONObject("iceParameters")
            val iceCandidates = transportInfo.optJSONArray("iceCandidates")
            val dtlsParameters = transportInfo.optJSONObject("dtlsParameters")
            if (iceParameters != null && dtlsParameters != null) {
                val candidatesObj = JSONObject().apply { put("candidates", iceCandidates) }
                mediasoupController.createTransport(direction, id, iceParameters, candidatesObj, dtlsParameters)
                if (direction == "send") {
                    if (androidMediasoupController.isDeviceLoaded()) {
                        androidMediasoupController.createSendTransport(transportInfo)
                    } else {
                        pendingSendTransportInfo = transportInfo
                    }
                } else if (direction == "recv") {
                    if (androidMediasoupController.isDeviceLoaded()) {
                        androidMediasoupController.createRecvTransport(transportInfo)
                    } else {
                        pendingRecvTransportInfo = transportInfo
                    }
                } else {
                    if (androidMediasoupController.isDeviceLoaded()) {
                        androidMediasoupController.createSendTransport(transportInfo)
                    } else {
                        pendingSendTransportInfo = transportInfo
                    }
                }
            }
        }

        override fun onRtpCapabilities(data: JSONObject) {
            rtpCapabilitiesJson = data
            if (!androidMediasoupController.isDeviceLoaded()) {
                mediasoupController.initDevice(data)
                androidMediasoupController.loadDevice(data)
            }
            pendingSendTransportInfo?.let {
                androidMediasoupController.createSendTransport(it)
                pendingSendTransportInfo = null
            }
            pendingRecvTransportInfo?.let {
                androidMediasoupController.createRecvTransport(it)
                pendingRecvTransportInfo = null
            }
        }

        override fun onNewProducer(data: JSONObject) {
            currentProducerId = data.getString("producerId")
            val caps = androidMediasoupController.getDeviceRtpCapabilities() ?: rtpCapabilitiesJson
            val tId = recvTransportId
            if (caps != null) {
                signalingClient.consumeAudio(currentProducerId!!, caps, tId)
            }
        }

        override fun onConsumerCreated(data: JSONObject) {
            uiScope.launch {
                androidMediasoupController.onConsumerCreated(data)
                if (!isTransmitting) {
                    signalingClient.resumeConsumer()
                    androidMediasoupController.setConsumersEnabled(true)
                } else {
                    signalingClient.pauseConsumer()
                    androidMediasoupController.setConsumersEnabled(false)
                }
            }
        }

        override fun onProducerOk(producerId: String) {
            Log.d("PTT", "Producer confirmed by server id=$producerId")
        }
    }

    private fun refreshData() {
        val cid = companyId ?: return
        uiScope.launch {
            val rec = loadRecents()
            recents.clear()
            recents.addAll(rec)
            val devs = fetchDevices(cid)
            contacts.clear()
            val selfId = deviceId
            contacts.addAll(
                if (selfId.isNullOrEmpty()) devs else devs.filter { it.id != selfId }
            )
            val pres = fetchPresence(cid)
            presenceMap.clear()
            presenceMap.putAll(pres)
            val chs = fetchChannelsWithCounts(cid, pres)
            channels.clear()
            channels.addAll(chs)
        }
    }

    private suspend fun fetchDevices(companyId: String): List<DeviceItem> {
        return withContext(Dispatchers.IO) {
            val url = Constants.SERVER_URL + "/v1/companies/" + companyId + "/devices"
            val req = Request.Builder().url(url).get().build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            val out = mutableListOf<DeviceItem>()
            var i = 0
            while (i < arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                val dn = o.optString("displayName")
                val an = o.optString("accountNumber")
                if (id.isNotEmpty()) out.add(DeviceItem(id, dn, an))
                i++
            }
            out
        }
    }

    private suspend fun fetchPresence(companyId: String): Map<String, PresenceInfo> {
        return withContext(Dispatchers.IO) {
            val url = Constants.SERVER_URL + "/v1/companies/" + companyId + "/presence"
            val req = Request.Builder().url(url).get().build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: "{}"
            val obj = JSONObject(body)
            val out = mutableMapOf<String, PresenceInfo>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optJSONObject(k) ?: JSONObject()
                val online = v.optBoolean("online", false)
                val lastSeen = if (v.has("lastSeen")) v.optLong("lastSeen") else null
                out[k] = PresenceInfo(online, lastSeen)
            }
            out
        }
    }

    private suspend fun fetchChannelsWithCounts(companyId: String, presence: Map<String, PresenceInfo>): List<ChannelItem> {
        return withContext(Dispatchers.IO) {
            val url = Constants.SERVER_URL + "/v1/companies/" + companyId + "/channels"
            val req = Request.Builder().url(url).get().build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            val out = mutableListOf<ChannelItem>()
            var i = 0
            while (i < arr.length()) {
                val ch = arr.getJSONObject(i)
                val cid = ch.optString("id")
                val name = ch.optString("name")
                var members = 0
                var online = 0
                var selfMember = false
                if (cid.isNotEmpty()) {
                    val murl = Constants.SERVER_URL + "/v1/channels/" + cid + "/members"
                    val mreq = Request.Builder().url(murl).get().build()
                    val mresp = httpClient.newCall(mreq).execute()
                    val mbody = mresp.body?.string() ?: "[]"
                    val marr = JSONArray(mbody)
                    var j = 0
                    while (j < marr.length()) {
                        val d = marr.getJSONObject(j)
                        val did = d.optString("id")
                        members++
                        if (presence[did]?.online == true) online++
                        if (!selfMember && deviceId != null && did == deviceId) selfMember = true
                        j++
                    }
                    if (selfMember) {
                        out.add(ChannelItem(cid, name, members, online))
                    }
                }
                i++
            }
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

    private suspend fun ensureDmChannel(otherDeviceId: String): String {
        return withContext(Dispatchers.IO) {
            val cid = companyId ?: return@withContext ""
            val selfId = deviceId ?: return@withContext ""
            val a = listOf(selfId, otherDeviceId).sorted()
            val dmName = "DM:" + a[0] + ":" + a[1]
            val curl = Constants.SERVER_URL + "/v1/companies/" + cid + "/channels"
            val req = Request.Builder().url(curl).get().build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: "[]"
            val arr = JSONArray(body)
            var existingId: String? = null
            var i = 0
            while (i < arr.length()) {
                val ch = arr.getJSONObject(i)
                if (ch.optString("name") == dmName) { existingId = ch.optString("id"); break }
                i++
            }
            val channelId = if (existingId != null) existingId!! else run {
                val payload = JSONObject().apply { put("name", dmName) }.toString()
                val reqBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val createReq = Request.Builder().url(curl).post(reqBody).build()
                val createResp = httpClient.newCall(createReq).execute()
                val cbody = createResp.body?.string() ?: "{}"
                JSONObject(cbody).optString("id")
            }
            if (channelId.isNotEmpty()) {
                ensureMembership(channelId, selfId)
                ensureMembership(channelId, otherDeviceId)
            }
            channelId
        }
    }

    private suspend fun startDirectContact(otherDeviceId: String, otherName: String) {
        if (deviceId != null && deviceId == otherDeviceId) {
            showToast("Select another contact")
            return
        }
        val chId = ensureDmChannel(otherDeviceId)
        if (chId.isNotEmpty()) {
            roomId = chId
            activeSessionName = otherName
            activeSessionType = "contact"
            signalingClient.joinChannel(chId, userId, userName)
            saveRecent(RecentItem("contact", otherDeviceId, otherName, System.currentTimeMillis()))
        } else {
            showToast("Unable to start contact talk")
        }
    }

    private suspend fun startChannelTalk(channelId: String, channelName: String) {
        val selfId = deviceId ?: return
        ensureMembership(channelId, selfId)
        roomId = channelId
        activeSessionName = channelName
        activeSessionType = "channel"
        signalingClient.joinChannel(channelId, userId, userName)
        saveRecent(RecentItem("channel", channelId, channelName, System.currentTimeMillis()))
    }

    private fun loadRecents(): List<RecentItem> {
        return runCatching {
            val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
            val s = prefs.getString("recents", "[]") ?: "[]"
            val arr = JSONArray(s)
            val out = mutableListOf<RecentItem>()
            var i = 0
            while (i < arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.optString("type")
                val id = o.optString("refId")
                val nm = o.optString("name")
                val ts = o.optLong("ts")
                if (t.isNotEmpty() && id.isNotEmpty()) out.add(RecentItem(t, id, nm, ts))
                i++
            }
            out
        }.getOrElse { emptyList() }
    }

    private fun saveRecent(item: RecentItem) {
        runCatching {
            recents.removeAll { it.type == item.type && it.refId == item.refId }
            recents.add(0, item)
            if (recents.size > 50) recents.removeLast()
            val arr = JSONArray()
            recents.forEach { r ->
                val o = JSONObject()
                o.put("type", r.type)
                o.put("refId", r.refId)
                o.put("name", r.name)
                o.put("ts", r.ts)
                arr.put(o)
            }
            val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
            prefs.edit().putString("recents", arr.toString()).apply()
        }
    }

    private fun removeRecent(item: RecentItem) {
        runCatching {
            recents.removeAll { it.type == item.type && it.refId == item.refId }
            val arr = JSONArray()
            recents.forEach { r ->
                val o = JSONObject()
                o.put("type", r.type)
                o.put("refId", r.refId)
                o.put("name", r.name)
                o.put("ts", r.ts)
                arr.put(o)
            }
            val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
            prefs.edit().putString("recents", arr.toString()).apply()
        }
    }

    private fun toggleSpeaker() {
        runCatching {
            val isSpeakerOn = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setSpeakerphoneOn(!isSpeakerOn)
            setVoiceCallVolumeFraction(0.9f)
        }
    }

    private fun performLogout() {
        runCatching {
            val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
            prefs.edit().remove("authToken").apply()
            authToken = null
            isAuthenticated = false
            roomId = ""
            activeSessionName = null
            activeSessionType = null
            signalingClient.disconnect()
        }
    }

    private fun endSession() {
        uiScope.launch {
            isTransmitting = false
            currentSpeaker = null
            stopTransmitting()
            if (roomId.isNotEmpty()) {
                signalingClient.leaveChannel(roomId, userId)
            }
            androidMediasoupController.reset()
            sendTransportId = null
            recvTransportId = null
            currentProducerId = null
            dtlsParametersJson = null
            rtpParametersJson = null
            pendingSendTransportInfo = null
            pendingRecvTransportInfo = null
            roomId = ""
            activeSessionName = null
            activeSessionType = null
        }
    }

    // WebRTC Listener
    private val webRTCListener = object : WebRTCManager.SignalingListener {
        override fun onLocalDescription(sdp: SessionDescription) {
            // Send SDP to server
            // TODO: Implement when we add proper WebRTC signaling
            Log.d("WebRTC", "Local description: ${sdp.type}")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            // Send ICE candidate to server
            val candidateJson = JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            // TODO: Send to server via signaling client
            Log.d("WebRTC", "ICE candidate: ${candidate.sdp}")
        }

        override fun onError(error: String) {
            Log.e("WebRTC", error)
            showToast("WebRTC error: $error")
        }

        override fun onConnected() {
            uiScope.launch {
                showToast("WebRTC connected!")
                Log.d("WebRTC", "WebRTC connected")
            }
        }

        override fun onDisconnected() {
            uiScope.launch {
                showToast("WebRTC disconnected")
                Log.d("WebRTC", "WebRTC disconnected")
            }
        }
    }

    // Hardware PTT button support (Volume Down)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("PTT", "Key down: $keyCode")
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownPressStart = System.currentTimeMillis()
                wasTransmittingBeforePress = isTransmitting
                if (hasPermission && isConnectedToServer && roomId.isNotEmpty()) {
                    uiScope.launch {
                        if (!isTransmitting) {
                            isTransmitting = true
                            currentSpeaker = "You"
                            startTransmitting()
                            signalingClient.requestSpeak(roomId, userId)
                            showToast("PTT Pressed (Volume Down)")
                        }
                    }
                    true
                } else {
                    showToast("Join a contact or channel first")
                    false
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("PTT", "Key up: $keyCode")
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val duration = System.currentTimeMillis() - volumeDownPressStart
                val isLongPress = duration >= longPressThresholdMs
                if (hasPermission && isConnectedToServer && roomId.isNotEmpty()) {
                    uiScope.launch {
                        val shouldStop = if (isLongPress) {
                            !wasTransmittingBeforePress
                        } else {
                            wasTransmittingBeforePress
                        }
                        if (shouldStop && isTransmitting) {
                            isTransmitting = false
                            currentSpeaker = null
                            stopTransmitting()
                            signalingClient.stopSpeaking(roomId, userId)
                            showToast("PTT Released")
                        }
                    }
                    true
                } else {
                    false
                }
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up
        signalingClient.disconnect()
        webRTCManager.cleanup()

        // Reset audio mode
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.setSpeakerphoneOn(false)
        } catch (e: Exception) {
            Log.e("PTT", "Cleanup failed", e)
        }
        Log.d("PTT", "MainActivity destroyed")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTabbedScreen(
    selectedTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    accountDisplayName: String,
    contacts: List<DeviceItem>,
    channels: List<ChannelItem>,
    presenceMap: Map<String, PresenceInfo>,
    recents: List<RecentItem>,
    showSettingsMenu: Boolean,
    onToggleSettingsMenu: () -> Unit,
    onLogout: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onContactClick: (DeviceItem) -> Unit,
    onChannelClick: (ChannelItem) -> Unit,
    onRecentClick: (RecentItem) -> Unit,
    onDeleteRecent: (RecentItem) -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("PTT") },
                actions = {
                    Text(
                        text = "⋮",
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable { onToggleSettingsMenu() }
                    )
                    DropdownMenu(expanded = showSettingsMenu, onDismissRequest = { onToggleSettingsMenu() }) {
                        DropdownMenuItem(text = { Text("Account: " + accountDisplayName) }, onClick = {}, enabled = false)
                        DropdownMenuItem(text = { Text("Logout") }, onClick = { onToggleSettingsMenu(); onLogout() })
                        DropdownMenuItem(text = { Text("Toggle Speaker") }, onClick = { onToggleSettingsMenu(); onToggleSpeaker() })
                    }
                }
            )

            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(selected = selectedTabIndex == 0, onClick = { onSelectTab(0) }, text = { Text("Recents") })
                Tab(selected = selectedTabIndex == 1, onClick = { onSelectTab(1) }, text = { Text("Contacts") })
                Tab(selected = selectedTabIndex == 2, onClick = { onSelectTab(2) }, text = { Text("Channels") })
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTabIndex) {
                    0 -> RecentsTab(recents = recents, onRecentClick = onRecentClick, onDeleteRecent = onDeleteRecent)
                    1 -> ContactsTab(contacts = contacts, presenceMap = presenceMap, onContactClick = onContactClick)
                    else -> ChannelsTab(channels = channels, onChannelClick = onChannelClick)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkScreen(
    title: String,
    isTransmitting: Boolean,
    currentSpeaker: String?,
    hasPermission: Boolean,
    isConnectedToServer: Boolean,
    onBack: () -> Unit,
    onPTTPressed: () -> Unit,
    onPTTReleased: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = {
                    Text(
                        text = "←",
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable { onBack() }
                    )
                },
                title = { Text(title) }
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StatusRow(
                        isTransmitting = isTransmitting,
                        currentSpeaker = currentSpeaker,
                        hasPermission = hasPermission,
                        isConnected = isConnectedToServer,
                        userId = "",
                        connectedCount = 0
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    PTTButton(
                        isPressed = isTransmitting,
                        enabled = hasPermission && isConnectedToServer,
                        connected = isConnectedToServer,
                        onPress = onPTTPressed,
                        onRelease = onPTTReleased
                    )
                }
            }
        }
    }
}

@Composable
fun StatusRow(
    isTransmitting: Boolean,
    currentSpeaker: String?,
    hasPermission: Boolean,
    isConnected: Boolean,
    userId: String,
    connectedCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = if (isTransmitting) "TRANSMITTING" else "LISTENING", color = if (isTransmitting) Color.Red else Color.Green)
        Text(text = currentSpeaker ?: "Idle")
        Text(text = if (hasPermission) "Mic ✓" else "Mic ✗", color = if (hasPermission) Color.Green else Color.Red)
        Text(text = if (isConnected) "Net ✓" else "Net ✗", color = if (isConnected) Color.Green else Color.Red)
        Text(text = userId)
        Text(text = connectedCount.toString())
    }
}

@Composable
fun RecentsTab(recents: List<RecentItem>, onRecentClick: (RecentItem) -> Unit, onDeleteRecent: (RecentItem) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(recents, key = { it.type + ":" + it.refId }) { r ->
            val density = LocalContext.current.resources.displayMetrics.density
            val thresholdPx = 120f * density
            var offsetX by remember { mutableStateOf(0f) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Red),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text("Delete", color = Color.White, modifier = Modifier.padding(16.dp))
                }

                Row(
                    modifier = Modifier
                        .offset { IntOffset(offsetX.roundToInt(), 0) }
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { _, dragAmount ->
                                    offsetX += dragAmount
                                },
                                onDragEnd = {
                                    if (abs(offsetX) > thresholdPx) {
                                        onDeleteRecent(r)
                                    }
                                    offsetX = 0f
                                }
                            )
                        }
                        .clickable { onRecentClick(r) }
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = r.name)
                        Text(text = if (r.type == "contact") "Contact" else "Channel")
                    }
                    Text(text = "Swipe to delete")
                }
            }
        }
    }
}

@Composable
fun ContactsTab(contacts: List<DeviceItem>, presenceMap: Map<String, PresenceInfo>, onContactClick: (DeviceItem) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(contacts) { d ->
            val online = presenceMap[d.id]?.online == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onContactClick(d) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = d.displayName)
                Text(text = if (online) "Online" else "Offline", color = if (online) Color.Green else Color.Red)
            }
        }
    }
}

@Composable
fun ChannelsTab(channels: List<ChannelItem>, onChannelClick: (ChannelItem) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(channels) { c ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChannelClick(c) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = c.name)
                Text(text = c.online.toString() + "/" + c.members.toString())
            }
        }
    }
}

@Composable
fun PTTButton(
    isPressed: Boolean,
    enabled: Boolean,
    connected: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    var isButtonPressed by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .size(200.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (enabled) {
                            isButtonPressed = true
                            onPress()
                            tryAwaitRelease()
                            isButtonPressed = false
                            onRelease()
                        }
                    }
                )
            },
        color = when {
            !enabled -> Color.Gray
            isPressed -> Color.Red
            else -> Color.Green
        },
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = if (isButtonPressed) 8.dp else 4.dp,
        shadowElevation = if (isButtonPressed) 8.dp else 4.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (!enabled) if (!connected) "NOT CONNECTED" else "NEEDS PERMISSION"
                    else if (isPressed) "SPEAKING"
                    else "PUSH TO TALK",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    companyId: String,
    accountNumber: String,
    password: String,
    isConnected: Boolean,
    isLoggingIn: Boolean,
    errorMessage: String?,
    onCompanyIdChange: (String) -> Unit,
    onAccountNumberChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit
) {
    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Device Login",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Surface(
                color = if (isConnected) Color.Green.copy(alpha = 0.2f) else Color.Red.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (isConnected) "✓ Connected" else "✗ Not Connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isConnected) Color.Green else Color.Red,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = companyId,
                onValueChange = onCompanyIdChange,
                label = { Text("Company ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = accountNumber,
                onValueChange = onAccountNumberChange,
                label = { Text("Account Number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation()
            )
            if (!errorMessage.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onLogin, enabled = isConnected && !isLoggingIn) {
                Text(if (isLoggingIn) "Logging in…" else "Login")
            }
        }
    }
}

data class DeviceItem(val id: String, val displayName: String, val accountNumber: String)
data class ChannelItem(val id: String, val name: String, val members: Int, val online: Int)
data class PresenceInfo(val online: Boolean, val lastSeen: Long?)
data class RecentItem(val type: String, val refId: String, val name: String, val ts: Long)
