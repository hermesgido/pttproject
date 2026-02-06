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
import com.ropex.pptapp.mediasoup.AndroidMediasoupController
import com.ropex.pptapp.config.AudioConfig
import com.ropex.pptapp.config.AudioRoute
import com.ropex.pptapp.config.VolumeStream
import com.ropex.pptapp.config.DeviceProfileRegistry
import com.ropex.pptapp.config.toAndroidStream
import com.ropex.pptapp.PTTManager
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.East
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import kotlin.math.max
import kotlin.math.min


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
    private lateinit var androidMediasoupController: AndroidMediasoupController
    private lateinit var httpClient: OkHttpClient
    private lateinit var pttManager: PTTManager

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
    private var audioConfig by mutableStateOf(DeviceProfileRegistry.defaultConfig())
    private var showAudioSettings by mutableStateOf(false)

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

        androidMediasoupController = AndroidMediasoupController(signalingClient)
        androidMediasoupController.initialize(this)

        httpClient = OkHttpClient()
        pttManager = PTTManager(this)

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
                } else if (roomId.isEmpty() && !showAudioSettings) {
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
                        onOpenAudioSettings = { showAudioSettings = true },
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
                } else if (!showAudioSettings) {
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
                                    pttManager.onPTTPressed()
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
                                    pttManager.onPTTReleased()
                                    isTransmitting = false
                                    currentSpeaker = null
                                    currentSpeakerId = null
                                    stopTransmitting()
                                    signalingClient.stopSpeaking(roomId, userId)
                                }
                            }
                        }
                    )
                } else {
                    AudioSettingsScreen(
                        audioConfig = audioConfig,
                        isTransmitting = isTransmitting,
                        onBack = { showAudioSettings = false },
                        onResetDefaults = {
                            val cfg = DeviceProfileRegistry.defaultConfig()
                            audioConfig = cfg
                            applyRxRouting()
                            applyWebRTCAudioConfig()
                            pttManager.setBeepConfig(cfg.beep.stream, cfg.beep.volumeFraction, cfg.beep.enabled)
                        },
                        onChangeRxRoute = {
                            audioConfig = audioConfig.copy(rxRoute = it)
                            applyRxRouting()
                        },
                        onChangeTxRoute = {
                            audioConfig = audioConfig.copy(txRoute = it)
                            if (isTransmitting) applyTxRouting()
                        },
                        onChangeVolumeStream = {
                            audioConfig = audioConfig.copy(volumeStream = it)
                            if (isTransmitting) applyTxRouting() else applyRxRouting()
                        },
                        onChangeRxVolume = {
                            audioConfig = audioConfig.copy(rxVolumeFraction = it)
                            applyRxRouting()
                        },
                        onChangeTxVolume = {
                            audioConfig = audioConfig.copy(txVolumeFraction = it)
                            if (isTransmitting) applyTxRouting()
                        },
                        onChangeMicSource = {
                            audioConfig = audioConfig.copy(micSource = it)
                            applyWebRTCAudioConfig()
                        },
                        onChangeAEC = {
                            audioConfig = audioConfig.copy(hardwareAEC = it)
                            applyWebRTCAudioConfig()
                        },
                        onChangeNS = {
                            audioConfig = audioConfig.copy(hardwareNS = it)
                            applyWebRTCAudioConfig()
                        },
                        onChangeAGC = {
                            audioConfig = audioConfig.copy(agcConstraint = it)
                            applyWebRTCAudioConfig()
                        },
                        onChangeHighpass = {
                            audioConfig = audioConfig.copy(highpassFilter = it)
                            applyWebRTCAudioConfig()
                        },
                        onChangeBeepEnabled = {
                            val cfg = audioConfig.copy(beep = audioConfig.beep.copy(enabled = it))
                            audioConfig = cfg
                            pttManager.setBeepConfig(cfg.beep.stream, cfg.beep.volumeFraction, cfg.beep.enabled)
                        },
                        onChangeBeepStream = {
                            val cfg = audioConfig.copy(beep = audioConfig.beep.copy(stream = it))
                            audioConfig = cfg
                            pttManager.setBeepConfig(cfg.beep.stream, cfg.beep.volumeFraction, cfg.beep.enabled)
                        },
                        onChangeBeepVolume = {
                            val cfg = audioConfig.copy(beep = audioConfig.beep.copy(volumeFraction = it))
                            audioConfig = cfg
                            pttManager.setBeepConfig(cfg.beep.stream, cfg.beep.volumeFraction, cfg.beep.enabled)
                        },
                        onChangeLatch = {
                            audioConfig = audioConfig.copy(ptt = audioConfig.ptt.copy(longPressLatch = it))
                        },
                        onChangeThreshold = {
                            audioConfig = audioConfig.copy(ptt = audioConfig.ptt.copy(longPressThresholdMs = it))
                        },
                        onChangePreferBluetooth = {
                            audioConfig = audioConfig.copy(accessory = audioConfig.accessory.copy(preferBluetooth = it))
                        },
                        onChangePreferWired = {
                            audioConfig = audioConfig.copy(accessory = audioConfig.accessory.copy(preferWiredHeadset = it))
                        }
                    )
                }
            }
        }

        // Initialize if permission already granted
        if (hasPermission) {
            setupAudio()
            initializeWebRTC()
            pttManager.setBeepConfig(audioConfig.beep.stream, audioConfig.beep.volumeFraction, audioConfig.beep.enabled)
        } else {
            requestAudioPermission()
        }
    }

    private fun setVolumeFraction(stream: VolumeStream, f: Float) {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(stream.toAndroidStream())
            val vol = max(1, min(maxVolume, (maxVolume * f).toInt()))
            audioManager.setStreamVolume(stream.toAndroidStream(), vol, 0)
        } catch (_: Exception) {}
    }

    private fun applyRxRouting() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setSpeakerphoneOn(audioConfig.rxRoute == AudioRoute.SPEAKER)
            setVolumeFraction(audioConfig.volumeStream, audioConfig.rxVolumeFraction)
        } catch (_: Exception) {}
    }

    private fun applyTxRouting() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.setSpeakerphoneOn(audioConfig.txRoute == AudioRoute.SPEAKER)
            setVolumeFraction(audioConfig.volumeStream, audioConfig.txVolumeFraction)
        } catch (_: Exception) {}
    }

    private fun applyWebRTCAudioConfig() {
        val iceServers = Constants.WebRTC.ICE_SERVERS.map { serverUrl ->
            PeerConnection.IceServer.builder(serverUrl).createIceServer()
        }
        webRTCManager.applyAudioConfig(audioConfig, iceServers)
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
            applyRxRouting()
            Log.d("PTT", "Audio setup complete")

        } catch (e: Exception) {
            Log.e("PTT", "Audio setup failed", e)
        }
    }

    private fun startTransmitting() {
        try {
            applyTxRouting()
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
            applyRxRouting()
            Log.d("PTT", "Stopped transmitting")
        } catch (e: Exception) {
            Log.e("PTT", "Stop transmitting failed", e)
        }
        androidMediasoupController.setConsumersEnabled(true)
        signalingClient.resumeConsumer()
    }

    private fun setVoiceCallVolumeFraction(f: Float) {}

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
                }
            }
        }

        override fun onRtpCapabilities(data: JSONObject) {
            rtpCapabilitiesJson = data
            if (!androidMediasoupController.isDeviceLoaded()) {
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
                        out.add(ChannelItem(
                            cid, name, members, online,
                            isMuted = ( false)
                        ))
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
            saveRecent(RecentItem(
                "contact", otherDeviceId, otherName, System.currentTimeMillis(),
                isMuted = (false),
                lastMessage = "",
                hasUnread = (false)
            ))
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
        saveRecent(RecentItem(
            "channel", channelId, channelName, System.currentTimeMillis(),
            isMuted = (false),
            lastMessage = "",
            hasUnread = (false)
        ))
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
                if (t.isNotEmpty() && id.isNotEmpty()) out.add(RecentItem(
                    t, id, nm, ts,
                    isMuted = (false),
                    lastMessage = "",
                    hasUnread = (false)
                ))
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
            setVolumeFraction(audioConfig.volumeStream, if (!isSpeakerOn) audioConfig.rxVolumeFraction else audioConfig.txVolumeFraction)
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
                            pttManager.onPTTPressed()
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
                val isLongPress = duration >= audioConfig.ptt.longPressThresholdMs
                if (hasPermission && isConnectedToServer && roomId.isNotEmpty()) {
                    uiScope.launch {
                        val shouldStop = if (audioConfig.ptt.longPressLatch) {
                            if (isLongPress) !wasTransmittingBeforePress else wasTransmittingBeforePress
                        } else {
                            true
                        }
                        if (shouldStop && isTransmitting) {
                            pttManager.onPTTReleased()
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
    onOpenAudioSettings: () -> Unit,
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
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.avator),
                            contentDescription = "User Avatar",
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        )

                        Column {
                            Text(
                                text = "Welcome back,",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = accountDisplayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
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
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Toggle Speaker") },
                                onClick = {
                                    onToggleSettingsMenu()
                                    onToggleSpeaker()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Audio & PTT Settings") },
                                onClick = {
                                    onToggleSettingsMenu()
                                    onOpenAudioSettings()
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
                        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            onSelectTab((selectedTabIndex + 2) % 3)
                            true
                        } else if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
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
fun AudioSettingsScreen(
    audioConfig: AudioConfig,
    isTransmitting: Boolean,
    onBack: () -> Unit,
    onResetDefaults: () -> Unit,
    onChangeRxRoute: (AudioRoute) -> Unit,
    onChangeTxRoute: (AudioRoute) -> Unit,
    onChangeVolumeStream: (VolumeStream) -> Unit,
    onChangeRxVolume: (Float) -> Unit,
    onChangeTxVolume: (Float) -> Unit,
    onChangeMicSource: (com.ropex.pptapp.config.MicSource) -> Unit,
    onChangeAEC: (Boolean) -> Unit,
    onChangeNS: (Boolean) -> Unit,
    onChangeAGC: (Boolean) -> Unit,
    onChangeHighpass: (Boolean) -> Unit,
    onChangeBeepEnabled: (Boolean) -> Unit,
    onChangeBeepStream: (VolumeStream) -> Unit,
    onChangeBeepVolume: (Float) -> Unit,
    onChangeLatch: (Boolean) -> Unit,
    onChangeThreshold: (Int) -> Unit,
    onChangePreferBluetooth: (Boolean) -> Unit,
    onChangePreferWired: (Boolean) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val isCompact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 360
        val pad = if (isCompact) 12.dp else 16.dp
        val space = if (isCompact) 8.dp else 16.dp
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Audio & PTT Settings") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Back") }
            })
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(pad),
                verticalArrangement = Arrangement.spacedBy(space)
            ) {
                item {
                    Card { Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(space)) {
                        Text("Routing")
                        if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.rxRoute == AudioRoute.SPEAKER, onClick = { onChangeRxRoute(AudioRoute.SPEAKER) })
                                    Text("RX: Speaker")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.rxRoute == AudioRoute.EARPIECE, onClick = { onChangeRxRoute(AudioRoute.EARPIECE) })
                                    Text("RX: Earpiece")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.txRoute == AudioRoute.SPEAKER, onClick = { onChangeTxRoute(AudioRoute.SPEAKER) })
                                    Text("TX: Speaker")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.txRoute == AudioRoute.EARPIECE, onClick = { onChangeTxRoute(AudioRoute.EARPIECE) })
                                    Text("TX: Earpiece")
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.rxRoute == AudioRoute.SPEAKER, onClick = { onChangeRxRoute(AudioRoute.SPEAKER) })
                                    Text("Receive: Speaker")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.rxRoute == AudioRoute.EARPIECE, onClick = { onChangeRxRoute(AudioRoute.EARPIECE) })
                                    Text("Receive: Earpiece")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.txRoute == AudioRoute.SPEAKER, onClick = { onChangeTxRoute(AudioRoute.SPEAKER) })
                                    Text("Transmit: Speaker")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.txRoute == AudioRoute.EARPIECE, onClick = { onChangeTxRoute(AudioRoute.EARPIECE) })
                                    Text("Transmit: Earpiece")
                                }
                            }
                        }
                    } }
                }
                item {
                    Card { Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(space)) {
                        Text("Volumes")
                        if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.volumeStream == VolumeStream.VOICE_CALL, onClick = { onChangeVolumeStream(VolumeStream.VOICE_CALL) })
                                    Text("Stream: Voice Call")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.volumeStream == VolumeStream.MUSIC, onClick = { onChangeVolumeStream(VolumeStream.MUSIC) })
                                    Text("Stream: Music")
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.volumeStream == VolumeStream.VOICE_CALL, onClick = { onChangeVolumeStream(VolumeStream.VOICE_CALL) })
                                    Text("Voice Call")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.volumeStream == VolumeStream.MUSIC, onClick = { onChangeVolumeStream(VolumeStream.MUSIC) })
                                    Text("Music")
                                }
                            }
                        }
                        Column { Text("Receive ${(audioConfig.rxVolumeFraction * 100).toInt()}%")
                            Slider(value = audioConfig.rxVolumeFraction, onValueChange = { onChangeRxVolume(it) }, valueRange = 0.1f..1.0f) }
                        Column { Text("Transmit ${(audioConfig.txVolumeFraction * 100).toInt()}%")
                            Slider(value = audioConfig.txVolumeFraction, onValueChange = { onChangeTxVolume(it) }, valueRange = 0.1f..1.0f) }
                    } }
                }
                item {
                    Card { Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(space)) {
                        Text("Microphone")
                        if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.micSource == com.ropex.pptapp.config.MicSource.VOICE_COMMUNICATION, onClick = { onChangeMicSource(com.ropex.pptapp.config.MicSource.VOICE_COMMUNICATION) })
                                    Text("Source: Voice Comm")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.micSource == com.ropex.pptapp.config.MicSource.MIC, onClick = { onChangeMicSource(com.ropex.pptapp.config.MicSource.MIC) })
                                    Text("Source: Mic")
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.hardwareAEC, onCheckedChange = { onChangeAEC(it) })
                                    Text("Hardware AEC")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.hardwareNS, onCheckedChange = { onChangeNS(it) })
                                    Text("Hardware NS")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.agcConstraint, onCheckedChange = { onChangeAGC(it) })
                                    Text("AGC")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.highpassFilter, onCheckedChange = { onChangeHighpass(it) })
                                    Text("Highpass Filter")
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.micSource == com.ropex.pptapp.config.MicSource.VOICE_COMMUNICATION, onClick = { onChangeMicSource(com.ropex.pptapp.config.MicSource.VOICE_COMMUNICATION) })
                                    Text("Voice Comm")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.micSource == com.ropex.pptapp.config.MicSource.MIC, onClick = { onChangeMicSource(com.ropex.pptapp.config.MicSource.MIC) })
                                    Text("Mic")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.hardwareAEC, onCheckedChange = { onChangeAEC(it) })
                                    Text("Hardware AEC")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.hardwareNS, onCheckedChange = { onChangeNS(it) })
                                    Text("Hardware NS")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.agcConstraint, onCheckedChange = { onChangeAGC(it) })
                                    Text("AGC")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.highpassFilter, onCheckedChange = { onChangeHighpass(it) })
                                    Text("Highpass Filter")
                                }
                            }
                        }
                    } }
                }
                item {
                    Card { Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(space)) {
                        Text("Beeps")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = audioConfig.beep.enabled, onCheckedChange = { onChangeBeepEnabled(it) })
                            Spacer(Modifier.width(space))
                            Text("Talk Permit")
                        }
                        if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.beep.stream == VolumeStream.VOICE_CALL, onClick = { onChangeBeepStream(VolumeStream.VOICE_CALL) })
                                    Text("Stream: Voice Call")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.beep.stream == VolumeStream.MUSIC, onClick = { onChangeBeepStream(VolumeStream.MUSIC) })
                                    Text("Stream: Music")
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.beep.stream == VolumeStream.VOICE_CALL, onClick = { onChangeBeepStream(VolumeStream.VOICE_CALL) })
                                    Text("Voice Call Stream")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = audioConfig.beep.stream == VolumeStream.MUSIC, onClick = { onChangeBeepStream(VolumeStream.MUSIC) })
                                    Text("Music Stream")
                                }
                            }
                        }
                        Column { Text("Beep ${(audioConfig.beep.volumeFraction * 100).toInt()}%")
                            Slider(value = audioConfig.beep.volumeFraction, onValueChange = { onChangeBeepVolume(it) }, valueRange = 0.1f..1.0f) }
                    } }
                }
                item {
                    Card { Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(space)) {
                        Text("PTT")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = audioConfig.ptt.longPressLatch, onCheckedChange = { onChangeLatch(it) })
                            Spacer(Modifier.width(space))
                            Text("Long-Press Latch")
                        }
                        Column { Text("Threshold ${audioConfig.ptt.longPressThresholdMs} ms")
                            Slider(value = audioConfig.ptt.longPressThresholdMs.toFloat(), onValueChange = { onChangeThreshold(it.toInt()) }, valueRange = 200f..1000f) }
                    } }
                }
                item {
                    Card { Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(space)) {
                        Text("Accessories")
                        if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.accessory.preferBluetooth, onCheckedChange = { onChangePreferBluetooth(it) })
                                    Spacer(Modifier.width(space))
                                    Text("Prefer Bluetooth")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.accessory.preferWiredHeadset, onCheckedChange = { onChangePreferWired(it) })
                                    Spacer(Modifier.width(space))
                                    Text("Prefer Wired Headset")
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.accessory.preferBluetooth, onCheckedChange = { onChangePreferBluetooth(it) })
                                    Text("Prefer Bluetooth")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = audioConfig.accessory.preferWiredHeadset, onCheckedChange = { onChangePreferWired(it) })
                                    Text("Prefer Wired Headset")
                                }
                            }
                        }
                    } }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(space)) {
                        Button(onClick = onResetDefaults) { Text("Reset to Defaults") }
                        Button(onClick = onBack) { Text("Back") }
                    }
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
                        if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                            onSelectSegment((selectedSegment + 3) % 4)
                            true
                        } else if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
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
                if (action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_SPACE)) {
                    if (!isButtonPressed) {
                        isButtonPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPress()
                    }
                    true
                } else if (action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_SPACE)) {
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

//
//@Composable
//fun SwipeableRecentItem(
//    item: RecentItem,
//    onClick: () -> Unit,
//    onDelete: () -> Unit
//) {
//    val density = LocalContext.current.resources.displayMetrics.density
//    val thresholdPx = 120f * density
//    var offsetX by remember { mutableStateOf(0f) }
//
//    Box(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(horizontal = 8.dp, vertical = 4.dp)
//    ) {
//        // Delete background
//        Surface(
//            modifier = Modifier
//                .fillMaxWidth()
//                .height(72.dp)
//                .clip(RoundedCornerShape(12.dp)),
//            color = Color(0xFFD32F2F)
//        ) {
//            Box(
//                modifier = Modifier.fillMaxSize(),
//                contentAlignment = Alignment.CenterEnd
//            ) {
//                Text(
//                    text = "Delete",
//                    color = Color.White,
//                    fontWeight = FontWeight.Bold,
//                    modifier = Modifier.padding(end = 24.dp)
//                )
//            }
//        }
//
//        // Swipeable content
//        Surface(
//            modifier = Modifier
//                .offset { IntOffset(offsetX.roundToInt(), 0) }
//                .fillMaxWidth()
//                .height(72.dp)
//                .clip(RoundedCornerShape(12.dp))
//                .pointerInput(Unit) {
//                    detectHorizontalDragGestures(
//                        onHorizontalDrag = { _, dragAmount ->
//                            offsetX = (offsetX + dragAmount).coerceIn(-thresholdPx * 2, 0f)
//                        },
//                        onDragEnd = {
//                            if (offsetX < -thresholdPx) {
//                                onDelete()
//                            }
//                            offsetX = 0f
//                        }
//                    )
//                }
//                .clickable(onClick = onClick),
//            color = MaterialTheme.colorScheme.surface,
//            shadowElevation = 2.dp
//        ) {
//            Row(
//                modifier = Modifier
//                    .fillMaxSize()
//                    .padding(horizontal = 16.dp, vertical = 12.dp),
//                verticalAlignment = Alignment.CenterVertically,
//                horizontalArrangement = Arrangement.SpaceBetween
//            ) {
//                Row(
//                    verticalAlignment = Alignment.CenterVertically,
//                    horizontalArrangement = Arrangement.spacedBy(12.dp),
//                    modifier = Modifier.weight(1f)
//                ) {
//                    // Icon
//                    Box(
//                        modifier = Modifier
//                            .size(48.dp)
//                            .clip(CircleShape)
//                            .background(
//                                if (item.type == "contact")
//                                    MaterialTheme.colorScheme.primaryContainer
//                                else
//                                    MaterialTheme.colorScheme.secondaryContainer
//                            ),
//                        contentAlignment = Alignment.Center
//                    ) {
//                        Text(
//                            text = if (item.type == "contact") "👤" else "📻",
//                            style = MaterialTheme.typography.titleLarge
//                        )
//                    }
//
//                    Column(modifier = Modifier.weight(1f)) {
//                        Text(
//                            text = item.name,
//                            style = MaterialTheme.typography.bodyLarge,
//                            fontWeight = FontWeight.Medium,
//                            maxLines = 1,
//                            overflow = TextOverflow.Ellipsis
//                        )
//                        Text(
//                            text = if (item.type == "contact") "Direct" else "Channel",
//                            style = MaterialTheme.typography.bodySmall,
//                            color = MaterialTheme.colorScheme.outline
//                        )
//                    }
//                }
//
//                Text(
//                    text = formatTime(item.ts),
//                    style = MaterialTheme.typography.bodySmall,
//                    color = MaterialTheme.colorScheme.outline
//                )
//            }
//        }
//    }
//}


@Composable
fun SwipeableRecentItem(
    item: RecentItem,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit = {},
    onMute: () -> Unit = {},
    onMarkUnread: () -> Unit = {}
) {
    val density = LocalContext.current.resources.displayMetrics.density
    val thresholdPx = 120f * density
    var offsetX by remember { mutableFloatStateOf(0f) }
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        // Delete background (revealed when swiping left)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(16.dp)),
            color = Color(0xFFD32F2F)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Delete",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // Swipeable content
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .fillMaxWidth()
                .height(76.dp)
                .clip(RoundedCornerShape(16.dp))
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
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = if (isPinned) 4.dp else 1.dp,
            shadowElevation = if (isPinned) 4.dp else 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon with gradient background
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(
                            if (item.type == "contact") CircleShape
                            else RoundedCornerShape(14.dp)
                        )
                        .background(
                            brush = Brush.linearGradient(
                                colors = if (item.type == "contact") listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer
                                ) else listOf(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.colorScheme.tertiaryContainer
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.type == "contact") {
                        // Show first letter for contacts
                        Text(
                            text = item.name.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        // Show emoji for channels
                        Text(
                            text = "📻",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }

                // Content
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (item.hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Pin indicator
                        if (isPinned) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Pinned",
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFFFFB300)
                            )
                        }

                        // Unread badge
                        if (item.hasUnread) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Type indicator with icon
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (item.type == "contact") Icons.Default.Person else Icons.Default.Tag,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (item.type == "contact") "Direct" else "Channel",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Last message preview (if available)
                        if (item.lastMessage != null) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )

                        }
                    }
                }

                // Time and menu
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = formatTime(item.ts),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.hasUnread)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.outline,
                        fontWeight = if (item.hasUnread) FontWeight.Bold else FontWeight.Normal
                    )

                    // Three-dot menu
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Dropdown menu
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(vertical = 4.dp)
                        ) {
                            // Pin/Unpin option
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isPinned) Icons.Default.Star else Icons.Outlined.Star,
                                            contentDescription = null,
                                            tint = if (isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (isPinned) "Unpin" else "Pin",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                },
                                onClick = {
                                    onPin()
                                    showMenu = false
                                },
                                leadingIcon = {}
                            )

                            // Mark as unread (if read)
                            if (!item.hasUnread) {
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MarkEmailUnread,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Mark as Unread",
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                        }
                                    },
                                    onClick = {
                                        onMarkUnread()
                                        showMenu = false
                                    },
                                    leadingIcon = {}
                                )
                            }

                            // Mute option
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (item.isMuted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (item.isMuted) "Unmute" else "Mute",
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                },
                                onClick = {
                                    onMute()
                                    showMenu = false
                                },
                                leadingIcon = {}
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )

                            // Delete option
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = Color(0xFFD32F2F)
                                        )
                                        Text(
                                            text = "Delete",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Color(0xFFD32F2F)
                                        )
                                    }
                                },
                                onClick = {
                                    onDelete()
                                    showMenu = false
                                },
                                leadingIcon = {}
                            )
                        }
                    }
                }
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


//
//
//@Composable
//fun ContactListItem(
//    contact: DeviceItem,
//    isOnline: Boolean,
//    onClick: () -> Unit
//) {
//    Surface(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(horizontal = 8.dp, vertical = 4.dp)
//            .clip(RoundedCornerShape(12.dp))
//            .clickable(onClick = onClick),
//        color = MaterialTheme.colorScheme.surface,
//        shadowElevation = 2.dp
//    ) {
//        Row(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(horizontal = 16.dp, vertical = 12.dp),
//            verticalAlignment = Alignment.CenterVertically,
//            horizontalArrangement = Arrangement.spacedBy(12.dp)
//        ) {
//            // Avatar with status
//            Box {
//                Box(
//                    modifier = Modifier
//                        .size(48.dp)
//                        .clip(CircleShape)
//                        .background(MaterialTheme.colorScheme.primaryContainer),
//                    contentAlignment = Alignment.Center
//                ) {
//                    Text(
//                        text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
//                        style = MaterialTheme.typography.titleLarge,
//                        fontWeight = FontWeight.Bold,
//                        color = MaterialTheme.colorScheme.onPrimaryContainer
//                    )
//                }
//                // Online status indicator
//                Box(
//                    modifier = Modifier
//                        .size(14.dp)
//                        .align(Alignment.BottomEnd)
//                        .clip(CircleShape)
//                        .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
//                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
//                )
//            }
//
//            Column(modifier = Modifier.weight(1f)) {
//                Text(
//                    text = contact.displayName,
//                    style = MaterialTheme.typography.bodyLarge,
//                    fontWeight = FontWeight.Medium,
//                    maxLines = 1,
//                    overflow = TextOverflow.Ellipsis
//                )
//                Text(
//                    text = if (isOnline) "Online" else "Offline",
//                    style = MaterialTheme.typography.bodySmall,
//                    color = if (isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
//                )
//            }
//
//            Text(
//                text = "→",
//                style = MaterialTheme.typography.titleMedium,
//                color = MaterialTheme.colorScheme.outline
//            )
//        }
//    }
//}

@Composable
fun ContactListItem(
    contact: DeviceItem,
    isOnline: Boolean,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onPin: () -> Unit = {},
    onCall: () -> Unit = {},
    onView: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (isPinned) 4.dp else 1.dp,
        shadowElevation = if (isPinned) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Avatar with online status indicator
            Box {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.displayName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                // Online status indicator with animation
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
                        .border(
                            width = 2.5.dp,
                            color = MaterialTheme.colorScheme.surface,
                            shape = CircleShape
                        )
                )
            }

            // Contact info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Pin indicator
                    if (isPinned) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFFFFB300)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isOnline) "Online" else "Offline",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isOnline) FontWeight.Medium else FontWeight.Normal,
                        color = if (isOnline)
                            Color(0xFF2E7D32)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Three-dot menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Dropdown menu
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(vertical = 1.dp)
                ) {
                    // Pin/Unpin option
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPinned) Icons.Default.Star else Icons.Outlined.Star,
                                    contentDescription = null,
                                    tint = if (isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isPinned) "Unpin Contact" else "Pin Contact",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        },
                        onClick = {
                            onPin()
                            showMenu = false
                        },
                        leadingIcon = {}
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // Call option
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50)
                                )
                                Text(
                                    text = "Call",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        },
                        onClick = {
                            onCall()
                            showMenu = false
                        },
                        enabled = isOnline,
                        leadingIcon = {}
                    )

                    // View option
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "View Profile",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        },
                        onClick = {
                            onView()
                            showMenu = false
                        },
                        leadingIcon = {}
                    )
                }
            }
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

//@Composable
//fun ChannelListItem(
//    channel: ChannelItem,
//    onClick: () -> Unit
//) {
//    Surface(
//        modifier = Modifier
//            .fillMaxWidth()
//            .padding(horizontal = 8.dp, vertical = 4.dp)
//            .clip(RoundedCornerShape(12.dp))
//            .clickable(onClick = onClick),
//        color = MaterialTheme.colorScheme.surface,
//        shadowElevation = 2.dp
//    ) {
//        Row(
//            modifier = Modifier
//                .fillMaxWidth()
//                .padding(horizontal = 16.dp, vertical = 12.dp),
//            verticalAlignment = Alignment.CenterVertically,
//            horizontalArrangement = Arrangement.spacedBy(12.dp)
//        ) {
//            // Channel icon
//            Box(
//                modifier = Modifier
//                    .size(48.dp)
//                    .clip(CircleShape)
//                    .background(MaterialTheme.colorScheme.secondaryContainer),
//                contentAlignment = Alignment.Center
//            ) {
//                Text(
//                    text = "📻",
//                    style = MaterialTheme.typography.titleLarge
//                )
//            }
//
//            Column(modifier = Modifier.weight(1f)) {
//                Text(
//                    text = channel.name,
//                    style = MaterialTheme.typography.bodyLarge,
//                    fontWeight = FontWeight.Medium,
//                    maxLines = 1,
//                    overflow = TextOverflow.Ellipsis
//                )
//                Row(
//                    horizontalArrangement = Arrangement.spacedBy(8.dp),
//                    verticalAlignment = Alignment.CenterVertically
//                ) {
//                    Text(
//                        text = "${channel.online} online",
//                        style = MaterialTheme.typography.bodySmall,
//                        color = Color(0xFF4CAF50)
//                    )
//                    Text(
//                        text = "•",
//                        style = MaterialTheme.typography.bodySmall,
//                        color = MaterialTheme.colorScheme.outline
//                    )
//                    Text(
//                        text = "${channel.members} members",
//                        style = MaterialTheme.typography.bodySmall,
//                        color = MaterialTheme.colorScheme.outline
//                    )
//                }
//            }
//
//            Text(
//                text = "→",
//                style = MaterialTheme.typography.titleMedium,
//                color = MaterialTheme.colorScheme.outline
//            )
//        }
//    }
//}


@Composable
fun ChannelListItem(
    channel: ChannelItem,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onPin: () -> Unit = {},
    onMute: () -> Unit = {},
    onView: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val cfg = LocalConfiguration.current
    val isCompact = cfg.screenWidthDp < 360
    val hPad = if (isCompact) 12.dp else 16.dp
    val vPad = if (isCompact) 8.dp else 16.dp
    val itemHPad = if (isCompact) 12.dp else 16.dp
    val itemVPad = if (isCompact) 4.dp else 6.dp
    val iconBox = if (isCompact) 40.dp else 56.dp
    val imageSize = if (isCompact) 28.dp else 40.dp
    val corner = if (isCompact) 12.dp else 16.dp
    val nameStyle = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
    val statStyle = if (isCompact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val menuSize = if (isCompact) 32.dp else 40.dp

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = itemHPad, vertical = itemVPad)
            .clip(RoundedCornerShape(corner))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = if (isPinned) 4.dp else 1.dp,
        shadowElevation = if (isPinned) 3.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad, vertical = vPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isCompact) 12.dp else 16.dp)
        ) {
            // Channel icon with gradient background
            Box(
                modifier = Modifier
                    .size(iconBox)
                    .clip(RoundedCornerShape(if (isCompact) 10.dp else 14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.channel),
                    contentDescription = "User Avatar",
                    modifier = Modifier
                        .size(imageSize)
                        .clip(CircleShape)
                )

            }

            // Channel info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = channel.name,
                        style = nameStyle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Pin indicator
                    if (isPinned) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(if (isCompact) 14.dp else 16.dp),
                            tint = Color(0xFFFFB300)
                        )
                    }

                    // Mute indicator (if channel is muted)
                    if (channel.isMuted) {
                        Icon(
                            imageVector = Icons.Default.VolumeOff,
                            contentDescription = "Muted",
                            modifier = Modifier.size(if (isCompact) 14.dp else 16.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                if (isCompact) {
                    Text(
                        text = "${channel.online} online • ${channel.members} mem",
                        style = statStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Text(
                                text = "${channel.online} online",
                                style = statStyle,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF2E7D32)
                            )
                        }
                        Text(
                            text = "•",
                            style = statStyle,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.People,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${channel.members} members",
                                style = statStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Three-dot menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(menuSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(vertical = 4.dp)
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPinned) Icons.Default.Star else Icons.Outlined.Star,
                                    contentDescription = null,
                                    tint = if (isPinned) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isPinned) "Unpin Channel" else "Pin Channel",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        },
                        onClick = {
                            onPin()
                            showMenu = false
                        },
                        leadingIcon = {}
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (channel.isMuted) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.VolumeOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (channel.isMuted) "Unmute Channel" else "Mute Channel",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        },
                        onClick = {
                            onMute()
                            showMenu = false
                        },
                        leadingIcon = {}
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "View Channel Info",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        },
                        onClick = {
                            onView()
                            showMenu = false
                        },
                        leadingIcon = {}
                    )
                }
            }
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
data class ChannelItem(val id: String, val name: String, val members: Int, val online: Int ,val isMuted: Boolean)
data class PresenceInfo(val online: Boolean, val lastSeen: Long?)
data class RecentItem(val type: String, val refId: String, val name: String, val ts: Long, val isMuted: Boolean, val lastMessage: Any, val hasUnread: Boolean)
data class MemberItem(val id: String, val name: String, val online: Boolean, val distanceKm: Long?)
