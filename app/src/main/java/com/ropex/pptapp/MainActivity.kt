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
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import com.ropex.pptapp.Constants
import com.ropex.pptapp.mediasoup.MediasoupController
import com.ropex.pptapp.mediasoup.AndroidMediasoupController

class MainActivity : ComponentActivity() {

    // State
    private var isTransmitting by mutableStateOf(false)
    private var currentSpeaker by mutableStateOf<String?>(null)
    private var hasPermission by mutableStateOf(false)
    private var isConnectedToServer by mutableStateOf(false)
    private var roomId by mutableStateOf("test-room")
    private var userId by mutableStateOf("user-${System.currentTimeMillis()}")
    private var userName by mutableStateOf("User ${(1..100).random()}")
    private var rtpCapabilitiesJson: JSONObject? = null
    private var sendTransportId: String? = null
    private var recvTransportId: String? = null
    private var currentProducerId: String? = null
    private var dtlsParametersJson: JSONObject? = null
    private var rtpParametersJson: JSONObject? = null
    private var pendingSendTransportInfo: JSONObject? = null
    private var pendingRecvTransportInfo: JSONObject? = null

    // Managers
    private lateinit var audioManager: AudioManager
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRTCManager: WebRTCManager
    private lateinit var mediasoupController: MediasoupController
    private lateinit var androidMediasoupController: AndroidMediasoupController

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

        Log.d("PTT", "MainActivity created")

        // Check initial permission
        hasPermission = checkMicrophonePermission()

        // Set UI content
        setContent {
            PPTAPPTheme {
                MainScreen(
                    isTransmitting = isTransmitting,
                    currentSpeaker = currentSpeaker,
                    hasPermission = hasPermission,
                    isConnectedToServer = isConnectedToServer,
                    onPTTPressed = {
                        if (hasPermission && isConnectedToServer) {
                            uiScope.launch {
                                isTransmitting = true
                                currentSpeaker = "You"
                                startTransmitting()
                                signalingClient.requestSpeak(roomId, userId)
                                Log.d("PTT", "PTT Pressed - Transmitting")
                            }
                        } else if (!hasPermission) {
                            showToast("Need microphone permission!")
                            requestAudioPermission()
                        } else if (!isConnectedToServer) {
                            showToast("Not connected to server!")
                        }
                    },
                    onPTTReleased = {
                        uiScope.launch {
                            isTransmitting = false
                            currentSpeaker = null
                            stopTransmitting()
                            signalingClient.stopSpeaking(roomId, userId)
                            Log.d("PTT", "PTT Released - Listening")
                        }
                    },
                    onJoinChannel = {
                        if (hasPermission && isConnectedToServer) {
                            signalingClient.joinChannel(roomId, userId, userName)
                            showToast("Joining channel...")
                            Log.d("PTT", "Joining channel")
                        } else {
                            showToast("Need permission and connection first!")
                        }
                    },
                    onLeaveChannel = {
                        uiScope.launch {
                            isTransmitting = false
                            currentSpeaker = null
                            stopTransmitting()
                            signalingClient.leaveChannel(roomId, userId)
                            webRTCManager.cleanup()
                            showToast("Left channel")
                            Log.d("PTT", "Left channel")
                        }
                    },
                    onRequestPermission = {
                        requestAudioPermission()
                    }
                )
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
            audioManager.isSpeakerphoneOn = true // Use speaker by default

            // Set reasonable volume
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume / 2, 0)

            Log.d("PTT", "Audio setup complete: mode=MODE_IN_COMMUNICATION, speakerphone=ON")

        } catch (e: Exception) {
            Log.e("PTT", "Audio setup failed", e)
        }
    }

    private fun startTransmitting() {
        // Start WebRTC transmission
        webRTCManager.startTransmitting()

        // Change audio mode
        try {
            audioManager.mode = AudioManager.MODE_IN_CALL
            Log.d("PTT", "Started transmitting")
        } catch (e: Exception) {
            Log.e("PTT", "Start transmitting failed", e)
        }
    }

    private fun stopTransmitting() {
        // Stop WebRTC transmission
        webRTCManager.stopTransmitting()

        // Change audio mode back
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            Log.d("PTT", "Stopped transmitting")
        } catch (e: Exception) {
            Log.e("PTT", "Stop transmitting failed", e)
        }
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
        }

        override fun onJoinError(error: String) {
            showToast("Join failed: $error")
        }

        override fun onSpeakGranted() {
            // Server granted permission to speak
            uiScope.launch {
                isTransmitting = true
                currentSpeaker = "You"
                startTransmitting()
                webRTCManager.getLocalAudioTrack()?.let { track ->
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
        }

        override fun onUserLeft(userId: String) {
            Log.d("Signaling", "User left: $userId")
        }

        override fun onIceCandidate(candidate: JSONObject) {
            // Handle ICE candidate from server
            // TODO: Implement when we add proper WebRTC signaling
        }

        override fun onTransportCreated(transportInfo: JSONObject) {
            val id = transportInfo.getString("id")
            val direction = transportInfo.optString("direction", "")
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
            mediasoupController.initDevice(data)
            androidMediasoupController.loadDevice(data)
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
            val caps = rtpCapabilitiesJson
            val tId = recvTransportId
            if (caps != null) {
                signalingClient.consumeAudio(currentProducerId!!, caps, tId)
            }
        }

        override fun onConsumerCreated(data: JSONObject) {
            uiScope.launch {
                androidMediasoupController.onConsumerCreated(data)
                signalingClient.resumeConsumer()
            }
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
                // Use volume down as PTT button
                if (hasPermission && isConnectedToServer) {
                    uiScope.launch {
                        isTransmitting = true
                        currentSpeaker = "You"
                        startTransmitting()
                        signalingClient.requestSpeak(roomId, userId)
                        showToast("PTT Pressed (Volume Down)")
                    }
                    true
                } else {
                    showToast("Need permission and connection!")
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
                uiScope.launch {
                    isTransmitting = false
                    currentSpeaker = null
                    stopTransmitting()
                    signalingClient.stopSpeaking(roomId, userId)
                    showToast("PTT Released")
                }
                true
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
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) {
            Log.e("PTT", "Cleanup failed", e)
        }
        Log.d("PTT", "MainActivity destroyed")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isTransmitting: Boolean,
    currentSpeaker: String?,
    hasPermission: Boolean,
    onPTTPressed: () -> Unit,
    onPTTReleased: () -> Unit,
    onJoinChannel: () -> Unit,
    onLeaveChannel: () -> Unit,
    isConnectedToServer: Boolean,
    onRequestPermission: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header with permission status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "PTT Communicator",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Permission status badge
                Surface(
                    color = if (hasPermission) Color.Green.copy(alpha = 0.2f)
                    else Color.Red.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (hasPermission) "✓ Microphone Granted"
                        else "✗ Microphone Needed",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasPermission) Color.Green else Color.Red,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    color = if (isConnectedToServer) Color.Green.copy(alpha = 0.2f)
                    else Color.Red.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = if (isConnectedToServer) "✓ Connected"
                        else "✗ Not Connected",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isConnectedToServer) Color.Green else Color.Red,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            // Status Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isTransmitting) Color.Red.copy(alpha = 0.1f)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isTransmitting) "TRANSMITTING" else "LISTENING",
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (isTransmitting) Color.Red else Color.Green
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = currentSpeaker ?: "No one speaking",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    // Visual indicator
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                color = if (isTransmitting) Color.Red else Color.Green,
                                shape = MaterialTheme.shapes.small
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // PTT Button - More responsive
            PTTButton(
                isPressed = isTransmitting,
                enabled = hasPermission && isConnectedToServer,
                connected = isConnectedToServer,
                onPress = onPTTPressed,
                onRelease = onPTTReleased
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = onJoinChannel,
                    enabled = hasPermission && isConnectedToServer
                ) {
                    Text("Join Channel")
                }

                Button(
                    onClick = onLeaveChannel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Leave")
                }
            }

            // Permission Button
            if (!hasPermission) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    ),
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Grant Microphone Permission")
                }
            }

            // Instructions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    text = "Hold button to speak, release to listen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Text(
                    text = "Volume Down = Hardware PTT",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "Status: ${if (isTransmitting) "Transmitting" else "Ready"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = if (isConnectedToServer) "Network: Connected" else "Network: Not Connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(top = 4.dp)
                )
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
