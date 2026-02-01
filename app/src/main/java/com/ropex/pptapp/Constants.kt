// /app/src/main/java/com/ropex/pptapp/Constants.kt
package com.ropex.pptapp

object Constants {

    // Server Configuration - CHANGE TO YOUR COMPUTER'S IP
    const val SERVER_URL = "http://192.168.1.132:3010"
    // For testing on same device as server:
    // const val SERVER_URL = "http://10.0.2.2:3000"  // Android emulator to localhost

    // WebSocket events
    object Events {
        const val CONNECT = "connect"
        const val DISCONNECT = "disconnect"
        const val JOIN_ROOM = "join-room"
        const val LEAVE_ROOM = "leave-room"
        const val REQUEST_SPEAK = "request-speak"
        const val STOP_SPEAKING = "stop-speaking"
        const val TRANSPORT_CREATED = "transport-created"
        const val CONNECT_TRANSPORT = "connect-transport"
        const val PRODUCE_AUDIO = "produce-audio"
        const val CONSUME_AUDIO = "consume-audio"
        const val RESUME_CONSUMER = "resume-consumer"
        const val PAUSE_CONSUMER = "pause-consumer"
        const val CONSUMER_CREATED = "consumer-created"

        // Server to client events
        const val JOIN_SUCCESS = "join-success"
        const val JOIN_ERROR = "join-error"
        const val SPEAK_GRANTED = "speak-granted"
        const val CHANNEL_BUSY = "channel-busy"
        const val USER_SPEAKING = "user-speaking"
        const val USER_STOPPED = "user-stopped"
        const val USER_JOINED = "user-joined"
        const val USER_LEFT = "user-left"
    }

    // WebRTC Configuration
    object WebRTC {
        // STUN servers for NAT traversal
        val ICE_SERVERS = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302"
        )

        // Audio configuration
        const val AUDIO_TRACK_ID = "audio_track"
        const val AUDIO_STREAM_ID = "audio_stream"
        const val USE_AGC = false
        const val USE_NOISE_GATE = true
        const val NOISE_GATE_RMS_THRESHOLD = 2000
        const val NOISE_GATE_ATTACK_MS = 20
        const val NOISE_GATE_RELEASE_MS = 250

        // Constraints
        val AUDIO_CONSTRAINTS = org.webrtc.MediaConstraints().apply {
            mandatory.add(org.webrtc.MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(org.webrtc.MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            if (USE_AGC) mandatory.add(org.webrtc.MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(org.webrtc.MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
    }

    // Room Configuration
    object Room {
        const val DEFAULT_ROOM_ID = "operations-room"
        const val DEFAULT_USER_NAME = "User"
    }
}


