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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback


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
    private var currentSpeakerId by mutableStateOf<String?>(null)
    private var talkSegmentIndex by mutableStateOf(1)

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
                        connectedUsersCount = connectedUsersCount,
                        members = connectedUsers.map { uid ->
                            val c = contacts.find { it.id == uid }
                            val nm = c?.displayName ?: uid
                            val online = presenceMap[uid]?.online == true
                            MemberItem(uid, nm, online, null)
                        },
                        currentSpeakerId = currentSpeakerId,
                        selectedSegment = talkSegmentIndex,
                        onSelectSegment = { talkSegmentIndex = it },
                        onBack = { endSession() },
                        onPTTPressed = {
                            if (hasPermission && isConnectedToServer && roomId.isNotEmpty()) {
                                uiScope.launch {
                                    isTransmitting = true
                                    currentSpeaker = "You"
                                    currentSpeakerId = userId
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
                                    currentSpeakerId = null
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

        handleOpenFromIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handleOpenFromIntent(intent)
    }

    private fun handleOpenFromIntent(intent: android.content.Intent) {
        val rid = intent.getStringExtra("open_room_id") ?: return
        val rname = intent.getStringExtra("open_room_name") ?: "Session"
        val rtype = intent.getStringExtra("open_room_type") ?: "channel"
        uiScope.launch {
            roomId = rid
            activeSessionName = rname
            activeSessionType = rtype
            runCatching {
                val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
                prefs.edit().putString("active_room_id", rid).apply()
            }
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
                try {
                    val intent = android.content.Intent(this@MainActivity, AlwaysOnPTTService::class.java)
                    startService(intent)
                } catch (_: Exception) {}
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
                currentSpeakerId = userId
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
                currentSpeaker = userName
                currentSpeakerId = userId
            }
        }

        override fun onUserStopped(userId: String) {
            uiScope.launch {
                currentSpeaker = null
                if (currentSpeakerId == userId) currentSpeakerId = null
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

        override fun onPage(roomId: String, fromUserId: String, fromUserName: String) {
            uiScope.launch {
                runCatching {
                    saveRecent(RecentItem("contact", fromUserId, fromUserName, System.currentTimeMillis()))
                }
            }
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
            runCatching {
                signalingClient.pageUser(otherDeviceId, chId, userId, userName)
            }
            runCatching {
                val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
                prefs.edit().putString("active_room_id", chId).apply()
            }
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
        runCatching {
            val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
            prefs.edit().putString("active_room_id", channelId).apply()
        }
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
            val isSpeakerOn = audioManager.isSpeakerphoneOn
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setSpeakerphoneOn(!isSpeakerOn)
            setVoiceCallVolumeFraction(if (!isSpeakerOn) 0.9f else 0.6f)
            showToast(if (!isSpeakerOn) "Speaker ON" else "Speaker OFF")
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
            runCatching {
                val prefs = getSharedPreferences("pptapp", MODE_PRIVATE)
                prefs.edit().remove("active_room_id").apply()
            }
        }
    }

    // WebRTC Listener
    private val webRTCListener = object : WebRTCManager.SignalingListener {
        override fun onLocalDescription(sdp: SessionDescription) {
            Log.d("WebRTC", "Local description: ${sdp.type}")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            val candidateJson = JSONObject().apply {
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
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
                        }
                    }
                    true
                } else {
                    if (roomId.isEmpty()) showToast("Join a contact or channel first")
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

// Modern UI Components - Zello-inspired

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
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Modern Top Bar
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PTT Radio",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Box {
                        IconButton(onClick = onToggleSettingsMenu) {
                            Text(
                                text = "⋮",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = onToggleSettingsMenu
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(
                                            text = accountDisplayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Logged in",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                },
                                onClick = {},
                                enabled = false
                            )
                            Divider()
                            DropdownMenuItem(
                                text = { Text("Toggle Speaker") },
                                onClick = {
                                    onToggleSettingsMenu()
                                    onToggleSpeaker()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Logout") },
                                onClick = {
                                    onToggleSettingsMenu()
                                    onLogout()
                                }
                            )
                        }
                    }
                }
            }

            // Modern Tab Bar
            TabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .focusTarget()
                    .onKeyEvent {
                        val action = it.nativeKeyEvent.action
                        val keyCode = it.nativeKeyEvent.keyCode
                        if (action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                            onSelectTab((selectedTabIndex + 2) % 3)
                            true
                        } else if (action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                            onSelectTab((selectedTabIndex + 1) % 3)
                            true
                        } else {
                            false
                        }
                    }
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { onSelectTab(0) },
                    text = {
                        Text(
                            text = "Recents",
                            fontWeight = if (selectedTabIndex == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { onSelectTab(1) },
                    text = {
                        Text(
                            text = "Contacts",
                            fontWeight = if (selectedTabIndex == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { onSelectTab(2) },
                    text = {
                        Text(
                            text = "Channels",
                            fontWeight = if (selectedTabIndex == 2) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }

            // Tab Content
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTabIndex) {
                    0 -> RecentsTab(
                        recents = recents,
                        onRecentClick = onRecentClick,
                        onDeleteRecent = onDeleteRecent
                    )
                    1 -> ContactsTab(
                        contacts = contacts,
                        presenceMap = presenceMap,
                        onContactClick = onContactClick
                    )
                    else -> ChannelsTab(
                        channels = channels,
                        onChannelClick = onChannelClick
                    )
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
    connectedUsersCount: Int,
    members: List<MemberItem>,
    currentSpeakerId: String?,
    selectedSegment: Int,
    onSelectSegment: (Int) -> Unit,
    onBack: () -> Unit,
    onPTTPressed: () -> Unit,
    onPTTReleased: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar with Back Button
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "$connectedUsersCount online",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            if (isConnectedToServer) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                )
                            }
                        }
                    }
                }
            }

            // Segmented control
            TabRow(
                selectedTabIndex = selectedSegment,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .focusTarget()
                    .onKeyEvent {
                        val action = it.nativeKeyEvent.action
                        val keyCode = it.nativeKeyEvent.keyCode
                        if (action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                            onSelectSegment((selectedSegment + 3) % 4)
                            true
                        } else if (action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                            onSelectSegment((selectedSegment + 1) % 4)
                            true
                        } else {
                            false
                        }
                    }
            ) {
                Tab(selected = selectedSegment == 0, onClick = { onSelectSegment(0) }, icon = { Icon(Icons.Filled.Mic, contentDescription = "Voice") })
                Tab(selected = selectedSegment == 1, onClick = { onSelectSegment(1) }, icon = { Icon(Icons.Filled.Person, contentDescription = "Members") })
                Tab(selected = selectedSegment == 2, onClick = { onSelectSegment(2) }, icon = { Icon(Icons.Filled.Chat, contentDescription = "Chat") })
                Tab(selected = selectedSegment == 3, onClick = { onSelectSegment(3) }, icon = { Icon(Icons.Filled.Map, contentDescription = "Map") })
            }

            // Main Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when (selectedSegment) {
                    0 -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        StatusCard(
                            isTransmitting = isTransmitting,
                            currentSpeaker = currentSpeaker,
                            hasPermission = hasPermission,
                            isConnected = isConnectedToServer
                        )
                        ModernPTTButton(
                            isPressed = isTransmitting,
                            enabled = hasPermission && isConnectedToServer,
                            connected = isConnectedToServer,
                            onPress = onPTTPressed,
                            onRelease = onPTTReleased
                        )
                    }
                    1 -> MembersList(members = members, activeSpeakerId = currentSpeakerId)
                    2 -> ChatPane()
                    else -> MapPane()
                }
            }
        }
    }
}

@Composable
fun MembersList(members: List<MemberItem>, activeSpeakerId: String?) {
    if (members.isEmpty()) {
        EmptyState(icon = "👥", message = "No members", subtitle = "Waiting for participants")
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .focusTarget(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(members, key = { it.id }) { m ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .focusTarget()
                        .semantics {
                            role = Role.Button
                            val speaking = m.id == activeSpeakerId
                            val status = if (m.online) "Online" else "Offline"
                            contentDescription = m.name + ", " + status + if (speaking) ", Speaking" else ""
                        },
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .border(
                                        width = if (m.id == activeSpeakerId) 3.dp else 0.dp,
                                        color = if (m.id == activeSpeakerId) Color(0xFF4CAF50) else Color.Transparent,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = m.name.firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column {
                                Text(
                                    text = m.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (m.online) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                                    )
                                    Text(
                                        text = if (m.online) "Online" else "Offline",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (m.online) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                                    )
                                    if (m.distanceKm != null) {
                                        Text(
                                            text = "•",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        Text(
                                            text = "${m.distanceKm} km",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                        if (m.id == activeSpeakerId) {
                            Icon(Icons.Filled.Mic, contentDescription = null, tint = Color(0xFF4CAF50))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatPane() {
    EmptyState(icon = "💬", message = "Chats", subtitle = "Messaging coming soon")
}

@Composable
fun MapPane() {
    EmptyState(icon = "🗺️", message = "Live map", subtitle = "Map integration pending")
}

@Composable
fun StatusCard(
    isTransmitting: Boolean,
    currentSpeaker: String?,
    hasPermission: Boolean,
    isConnected: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(
            containerColor = if (isTransmitting)
                Color(0xFFFFEBEE)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Main Status
            Text(
                text = if (isTransmitting) "TRANSMITTING" else "LISTENING",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isTransmitting) Color(0xFFD32F2F) else Color(0xFF388E3C)
            )

            // Current Speaker
            if (currentSpeaker != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD32F2F))
                    )
                    Text(
                        text = currentSpeaker,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    text = "Channel idle",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Divider(modifier = Modifier.padding(vertical = 4.dp))

            // System Status Icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusIcon(
                    label = "Mic",
                    isActive = hasPermission,
                    icon = if (hasPermission) "🎤" else "🚫"
                )
                StatusIcon(
                    label = "Network",
                    isActive = isConnected,
                    icon = if (isConnected) "📡" else "⚠️"
                )
            }
        }
    }
}

@Composable
fun StatusIcon(label: String, isActive: Boolean, icon: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isActive) Color(0xFF388E3C) else Color(0xFFD32F2F),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ModernPTTButton(
    isPressed: Boolean,
    enabled: Boolean,
    connected: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    var isButtonPressed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    var hasFocus by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val buttonColor = when {
        !enabled -> Color(0xFF9E9E9E)
        isPressed -> Color(0xFFD32F2F)
        else -> Color(0xFF4CAF50)
    }

    Box(
        modifier = Modifier
            .size(if (isPressed) 220.dp else 200.dp)
            .focusRequester(focusRequester)
            .focusTarget()
            .onFocusChanged { hasFocus = it.isFocused }
            .semantics {
                role = Role.Button
                contentDescription = when {
                    !enabled -> if (!connected) "Not Connected" else "No Permission"
                    isPressed -> "Speaking"
                    else -> "Push To Talk"
                }
            }
            .onKeyEvent {
                if (!enabled) return@onKeyEvent false
                val action = it.nativeKeyEvent.action
                val keyCode = it.nativeKeyEvent.keyCode
                if (action == android.view.KeyEvent.ACTION_DOWN &&
                    (keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_SPACE)) {
                    if (!isButtonPressed) {
                        isButtonPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPress()
                    }
                    true
                } else if (action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_SPACE)) {
                    if (isButtonPressed) {
                        isButtonPressed = false
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRelease()
                    }
                    true
                } else {
                    false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (enabled) {
                            isButtonPressed = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPress()
                            tryAwaitRelease()
                            isButtonPressed = false
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRelease()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer glow effect when transmitting
        if (isPressed) {
            Box(
                modifier = Modifier
                    .size(240.dp * pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFD32F2F).copy(alpha = 0.3f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        if (hasFocus && !isPressed) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF4CAF50).copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Main button
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = buttonColor,
            shape = CircleShape,
            shadowElevation = if (isButtonPressed) 12.dp else 8.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when {
                            !enabled -> if (!connected) "🔌" else "🚫"
                            isPressed -> "📢"
                            else -> "🎙️"
                        },
                        style = MaterialTheme.typography.displayLarge
                    )
                    Text(
                        text = when {
                            !enabled -> if (!connected) "NOT CONNECTED" else "NO PERMISSION"
                            isPressed -> "SPEAKING"
                            else -> "PUSH TO TALK"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun RecentsTab(
    recents: List<RecentItem>,
    onRecentClick: (RecentItem) -> Unit,
    onDeleteRecent: (RecentItem) -> Unit
) {
    if (recents.isEmpty()) {
        EmptyState(
            icon = "🕐",
            message = "No recent conversations",
            subtitle = "Start a conversation with a contact or channel"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(recents, key = { it.type + ":" + it.refId }) { item ->
                SwipeableRecentItem(
                    item = item,
                    onClick = { onRecentClick(item) },
                    onDelete = { onDeleteRecent(item) }
                )
            }
        }
    }
}

@Composable
fun SwipeableRecentItem(
    item: RecentItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val thresholdPx = 120f * density
    var offsetX by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        // Delete background
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp)),
            color = Color(0xFFD32F2F)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = "Delete",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 24.dp)
                )
            }
        }

        // Swipeable content
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            offsetX = (offsetX + dragAmount).coerceIn(-thresholdPx * 2, 0f)
                        },
                        onDragEnd = {
                            if (offsetX < -thresholdPx) {
                                onDelete()
                            }
                            offsetX = 0f
                        }
                    )
                }
                .clickable(onClick = onClick),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // Icon
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (item.type == "contact")
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.secondaryContainer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (item.type == "contact") "👤" else "📻",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (item.type == "contact") "Direct" else "Channel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Text(
                    text = formatTime(item.ts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun ContactsTab(
    contacts: List<DeviceItem>,
    presenceMap: Map<String, PresenceInfo>,
    onContactClick: (DeviceItem) -> Unit
) {
    if (contacts.isEmpty()) {
        EmptyState(
            icon = "👥",
            message = "No contacts available",
            subtitle = "Contacts will appear here when added"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(contacts) { contact ->
                ContactListItem(
                    contact = contact,
                    isOnline = presenceMap[contact.id]?.online == true,
                    onClick = { onContactClick(contact) }
                )
            }
        }
    }
}

@Composable
fun ContactListItem(
    contact: DeviceItem,
    isOnline: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Avatar with status
            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                // Online status indicator
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isOnline) "Online" else "Offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                )
            }

            Text(
                text = "→",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun ChannelsTab(
    channels: List<ChannelItem>,
    onChannelClick: (ChannelItem) -> Unit
) {
    if (channels.isEmpty()) {
        EmptyState(
            icon = "📻",
            message = "No channels available",
            subtitle = "Join a channel to start communicating"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(channels) { channel ->
                ChannelListItem(
                    channel = channel,
                    onClick = { onChannelClick(channel) }
                )
            }
        }
    }
}

@Composable
fun ChannelListItem(
    channel: ChannelItem,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Channel icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📻",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${channel.online} online",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50)
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "${channel.members} members",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Text(
                text = "→",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun EmptyState(icon: String, message: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                text = message,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo/Icon
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "📻",
                    style = MaterialTheme.typography.displayLarge
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "PTT Radio",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Connection Status
            Surface(
                color = if (isConnected)
                    Color(0xFF4CAF50).copy(alpha = 0.2f)
                else
                    Color(0xFFD32F2F).copy(alpha = 0.2f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Color(0xFF4CAF50) else Color(0xFFD32F2F))
                    )
                    Text(
                        text = if (isConnected) "Connected" else "Not Connected",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isConnected) Color(0xFF4CAF50) else Color(0xFFD32F2F)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Input Fields
            OutlinedTextField(
                value = companyId,
                onValueChange = onCompanyIdChange,
                label = { Text("Company ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = accountNumber,
                onValueChange = onAccountNumberChange,
                label = { Text("Account Number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            // Error Message
            if (!errorMessage.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = Color(0xFFD32F2F).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFD32F2F),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Login Button
            Button(
                onClick = onLogin,
                enabled = isConnected && !isLoggingIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoggingIn) {
                    Text("Logging in...")
                } else {
                    Text(
                        text = "Login",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// Helper function to format timestamps
fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        diff < 86400000 -> "${diff / 3600000}h ago"
        diff < 604800000 -> "${diff / 86400000}d ago"
        else -> {
            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

// Data classes
data class DeviceItem(val id: String, val displayName: String, val accountNumber: String)
data class ChannelItem(val id: String, val name: String, val members: Int, val online: Int)
data class PresenceInfo(val online: Boolean, val lastSeen: Long?)
data class RecentItem(val type: String, val refId: String, val name: String, val ts: Long)
data class MemberItem(val id: String, val name: String, val online: Boolean, val distanceKm: Long?)
