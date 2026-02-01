package com.ropex.pptapp.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WebRTCClient(
    private val context: Context,
    private val listener: WebRTCListener
) {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var peerConnection: PeerConnection
    private lateinit var localAudioTrack: AudioTrack
    private var audioSource: AudioSource? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    interface WebRTCListener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onIceConnectionChange(state: PeerConnection.IceConnectionState)
        fun onAddStream(stream: MediaStream)
        fun onDataChannel(channel: DataChannel)
        fun onError(error: String)
    }

    fun initialize() {
        executor.execute {
            try {
                // Initialize WebRTC - SIMPLER VERSION
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )

                // Create factory with audio only - SIMPLIFIED
                val options = PeerConnectionFactory.Options()

                // Create default audio encoder/decoder factories
                // The exact method depends on your WebRTC version
                peerConnectionFactory = try {
                    // Try method 1: For newer WebRTC versions
                    PeerConnectionFactory.builder()
                        .setOptions(options)
                        .createPeerConnectionFactory()
                } catch (e: Exception) {
                    // Try method 2: For older versions
                    PeerConnectionFactory.builder()
                        .createPeerConnectionFactory()
                }

                Log.d("WebRTC", "PeerConnectionFactory initialized")

            } catch (e: Exception) {
                Log.e("WebRTC", "Initialization failed", e)
                listener.onError("WebRTC init failed: ${e.message}")
            }
        }
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer>) {
        executor.execute {
            try {
                val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                    // Configure for PTT audio
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    iceCandidatePoolSize = 2
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                    candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
                }

                peerConnection = peerConnectionFactory.createPeerConnection(
                    rtcConfig,
                    object : PeerConnection.Observer {
                        override fun onSignalingChange(state: PeerConnection.SignalingState) {
                            Log.d("WebRTC", "Signaling state: $state")
                        }

                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                            Log.d("WebRTC", "ICE connection state: $state")
                            listener.onIceConnectionChange(state)
                        }

                        override fun onIceConnectionReceivingChange(receiving: Boolean) {
                            Log.d("WebRTC", "ICE receiving: $receiving")
                        }

                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                            Log.d("WebRTC", "ICE gathering state: $state")
                        }

                        override fun onIceCandidate(candidate: IceCandidate) {
                            Log.d("WebRTC", "New ICE candidate: ${candidate.sdp}")
                            listener.onIceCandidate(candidate)
                        }

                        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
                            Log.d("WebRTC", "ICE candidates removed")
                        }

                        override fun onAddStream(stream: MediaStream) {
                            Log.d("WebRTC", "Stream added: ${stream.id}")
                            listener.onAddStream(stream)
                        }

                        override fun onRemoveStream(stream: MediaStream) {
                            Log.d("WebRTC", "Stream removed: ${stream.id}")
                        }

                        override fun onDataChannel(channel: DataChannel) {
                            Log.d("WebRTC", "Data channel: ${channel.label()}")
                            listener.onDataChannel(channel)
                        }

                        override fun onRenegotiationNeeded() {
                            Log.d("WebRTC", "Renegotiation needed")
                        }

                        override fun onAddTrack(
                            receiver: RtpReceiver,
                            mediaStreams: Array<MediaStream>
                        ) {
                            Log.d("WebRTC", "Track added")
                        }
                    }
                ) ?: throw IllegalStateException("Failed to create PeerConnection")

                Log.d("WebRTC", "PeerConnection created")

            } catch (e: Exception) {
                Log.e("WebRTC", "Create PeerConnection failed", e)
                listener.onError("Create PeerConnection failed: ${e.message}")
            }
        }
    }

    fun createLocalAudioTrack() {
        executor.execute {
            try {
                // Create audio source with constraints
                val audioConstraints = MediaConstraints().apply {
                    // These are optional - WebRTC has good defaults
                    optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                }

                audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
                localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource!!)
                localAudioTrack.setEnabled(false) // Start muted

                Log.d("WebRTC", "Local audio track created")

            } catch (e: Exception) {
                Log.e("WebRTC", "Create audio track failed", e)
                // Fallback: create without constraints
                try {
                    audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
                    localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource!!)
                    localAudioTrack.setEnabled(false)
                    Log.d("WebRTC", "Local audio track created (fallback)")
                } catch (e2: Exception) {
                    listener.onError("Create audio track failed: ${e2.message}")
                }
            }
        }
    }

    fun addLocalAudioTrack() {
        executor.execute {
            try {
                if (::peerConnection.isInitialized && ::localAudioTrack.isInitialized) {
                    peerConnection.addTrack(localAudioTrack, listOf("audio_stream"))
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

    fun close() {
        executor.execute {
            try {
                audioSource?.dispose()
                if (::peerConnection.isInitialized) {
                    peerConnection.close()
                }
                if (::peerConnectionFactory.isInitialized) {
                    peerConnectionFactory.dispose()
                }
                Log.d("WebRTC", "WebRTC resources released")
            } catch (e: Exception) {
                Log.e("WebRTC", "Close failed", e)
            }
        }
        executor.shutdown()
    }
}