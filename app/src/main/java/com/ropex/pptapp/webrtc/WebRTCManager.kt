package com.ropex.pptapp.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
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
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )

                // Create factory with audio
                peerConnectionFactory = PeerConnectionFactory.builder()
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
                // Use simplified constraints - some WebRTC versions don't support all options
                val audioConstraints = MediaConstraints().apply {
                    // Only add optional constraints
                    optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                }

                audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
                localAudioTrack = peerConnectionFactory.createAudioTrack(
                    Constants.WebRTC.AUDIO_TRACK_ID,
                    audioSource!!
                )
                localAudioTrack.setEnabled(false) // Start muted
                Log.d("WebRTC", "Local audio track created")
            } catch (e: Exception) {
                Log.e("WebRTC", "Create audio track failed", e)
                // Fallback: create without constraints
                try {
                    audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
                    localAudioTrack = peerConnectionFactory.createAudioTrack(
                        Constants.WebRTC.AUDIO_TRACK_ID,
                        audioSource!!
                    )
                    localAudioTrack.setEnabled(false)
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

    fun getLocalAudioTrack(): AudioTrack? = if (::localAudioTrack.isInitialized) localAudioTrack else null
}
