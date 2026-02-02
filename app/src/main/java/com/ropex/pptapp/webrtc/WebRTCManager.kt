package com.ropex.pptapp.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import org.webrtc.audio.AudioDeviceModule
import java.util.concurrent.Executors
import com.ropex.pptapp.Constants

class WebRTCManager(
    private val context: Context,
    private val signalingListener: SignalingListener
) {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var peerConnection: PeerConnection
    private lateinit var localAudioTrack: AudioTrack
    private var audioSource: AudioSource? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var isTransmittingFlag: Boolean = false

    private var isInitialized = false
    private var isConnected = false

    interface SignalingListener {
        fun onLocalDescription(sdp: SessionDescription)
        fun onIceCandidate(candidate: IceCandidate)
        fun onError(error: String)
        fun onConnected()
        fun onDisconnected()
    }

    fun initialize() {
        executor.execute {
            try {
                Log.d("WebRTC", "Initializing WebRTC...")

                // Initialize WebRTC
//                PeerConnectionFactory.initialize(
//                    PeerConnectionFactory.InitializationOptions.builder(context)
//                        .createInitializationOptions()
//                )

                val options = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setFieldTrials("WebRTC-IntelVP8/Enabled/")
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(options)
                Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE)


                audioDeviceModule = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(true)
                    .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .createAudioDeviceModule()

                val factoryOptions = PeerConnectionFactory.Options()

                peerConnectionFactory =  PeerConnectionFactory.builder()
                    .setOptions(factoryOptions)
                    .setAudioDeviceModule(audioDeviceModule)
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(null, false, false))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(null))
                    .createPeerConnectionFactory()


                isInitialized = true
                Log.d("WebRTC", "WebRTC initialized successfully")

            } catch (e: Exception) {
                Log.e("WebRTC", "Initialization failed", e)
                signalingListener.onError("WebRTC init failed: ${e.message}")
            }
        }
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        executor.execute {
            try {
                if (!isInitialized) {
                    signalingListener.onError("WebRTC not initialized")
                    return@execute
                }

                val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    iceCandidatePoolSize = 2
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                }

                peerConnection = peerConnectionFactory.createPeerConnection(
                    rtcConfig,
                    object : PeerConnection.Observer {
                        // Required methods for the interface
                        override fun onSignalingChange(state: PeerConnection.SignalingState) {
                            Log.d("WebRTC", "Signaling state: $state")
                        }

                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                            Log.d("WebRTC", "ICE connection: $state")
                            when (state) {
                                PeerConnection.IceConnectionState.CONNECTED -> {
                                    isConnected = true
                                    signalingListener.onConnected()
                                }
                                PeerConnection.IceConnectionState.DISCONNECTED,
                                PeerConnection.IceConnectionState.FAILED,
                                PeerConnection.IceConnectionState.CLOSED -> {
                                    isConnected = false
                                    signalingListener.onDisconnected()
                                }
                                else -> {}
                            }
                        }

                        override fun onIceConnectionReceivingChange(receiving: Boolean) {
                            Log.d("WebRTC", "ICE receiving: $receiving")
                        }

                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                            Log.d("WebRTC", "ICE gathering: $state")
                        }

                        override fun onIceCandidate(candidate: IceCandidate) {
                            Log.d("WebRTC", "New ICE candidate: ${candidate.sdp}")
                            signalingListener.onIceCandidate(candidate)
                        }

                        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
                            Log.d("WebRTC", "ICE candidates removed: ${candidates.size}")
                        }

                        override fun onAddStream(stream: MediaStream) {
                            Log.d("WebRTC", "Stream added: ${stream.id}")
                            // Handle incoming audio stream
                        }

                        override fun onRemoveStream(stream: MediaStream) {
                            Log.d("WebRTC", "Stream removed: ${stream.id}")
                        }

                        override fun onDataChannel(channel: DataChannel) {
                            Log.d("WebRTC", "Data channel: ${channel.label()}")
                        }

                        override fun onRenegotiationNeeded() {
                            Log.d("WebRTC", "Renegotiation needed")
                        }

                        override fun onAddTrack(
                            receiver: RtpReceiver,
                            mediaStreams: Array<MediaStream>
                        ) {
                            Log.d("WebRTC", "Track added: ${receiver.id()}")
                        }

                        override fun onRemoveTrack(receiver: RtpReceiver) {
                            Log.d("WebRTC", "Track removed: ${receiver.id()}")
                        }
                    }
                ) ?: throw IllegalStateException("Failed to create PeerConnection")

                Log.d("WebRTC", "PeerConnection created")

            } catch (e: Exception) {
                Log.e("WebRTC", "Create PeerConnection failed", e)
                signalingListener.onError("Create PeerConnection failed: ${e.message}")
            }
        }
    }

    fun createLocalAudioTrack() {
        executor.execute {
            try {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                }

                audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
                localAudioTrack = peerConnectionFactory.createAudioTrack(
                    "PTT_AUDIO_01",
                    audioSource!!
                )
                localAudioTrack.setEnabled(true) // Start muted
                Log.d("WebRTC", "Local audio track created")
            } catch (e: Exception) {
                Log.e("WebRTC", "Create audio track failed", e)
                try {
                    val fallbackConstraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    }
                    audioSource = peerConnectionFactory.createAudioSource(fallbackConstraints)
                    localAudioTrack = peerConnectionFactory.createAudioTrack(
                        "PTT_AUDIO_01",
                        audioSource!!
                    )
                    localAudioTrack.setEnabled(true)
                    Log.d("WebRTC", "Local audio track created (fallback)")
                } catch (e2: Exception) {
                    signalingListener.onError("Create audio track failed: ${e2.message}")
                }
            }
        }
    }

    fun addLocalAudioTrack() {
        executor.execute {
            try {
                if (::peerConnection.isInitialized && ::localAudioTrack.isInitialized) {
                    peerConnection.addTrack(localAudioTrack, listOf(Constants.WebRTC.AUDIO_STREAM_ID))
                    Log.d("WebRTC", "Local audio track added to PeerConnection")
                }
            } catch (e: Exception) {
                Log.e("WebRTC", "Add audio track failed", e)
            }
        }
    }

    fun startTransmitting() {
        executor.execute {
            if (::localAudioTrack.isInitialized) {
                localAudioTrack.setEnabled(true)
                Log.d("WebRTC", "Transmission started")
            }
        }
    }

    fun startTransmittingWithDelay(delayMs: Long) {
        executor.execute {
            try {
                Thread.sleep(delayMs)
            } catch (_: Exception) {}
            if (::localAudioTrack.isInitialized) {
                localAudioTrack.setEnabled(true)
                Log.d("WebRTC", "Transmission started")
            }
        }
    }

    fun stopTransmitting() {
        executor.execute {
            if (::localAudioTrack.isInitialized) {
                localAudioTrack.setEnabled(false)
                Log.d("WebRTC", "Transmission stopped")
            }
        }
    }

    fun cleanup() {
        executor.execute {
            try {
                audioSource?.dispose()
                audioDeviceModule?.release()
                if (::peerConnection.isInitialized) {
                    peerConnection.close()
                }
                if (::peerConnectionFactory.isInitialized) {
                    peerConnectionFactory.dispose()
                }
                isConnected = false
                isInitialized = false
                Log.d("WebRTC", "WebRTC resources cleaned up")
            } catch (e: Exception) {
                Log.e("WebRTC", "Cleanup failed", e)
            }
        }
        executor.shutdown()
    }

    fun isReady(): Boolean = isInitialized && isConnected

    fun setNoiseGate(enabled: Boolean, thresholdRms: Int, attackMs: Int, releaseMs: Int) {}

    fun setTransmitState(isTransmitting: Boolean) {
        executor.execute {
            isTransmittingFlag = isTransmitting
            if (!::localAudioTrack.isInitialized) return@execute
            localAudioTrack.setEnabled(isTransmitting)
        }
    }

    fun getLocalAudioTrack(): AudioTrack? = if (::localAudioTrack.isInitialized) localAudioTrack else null
}
