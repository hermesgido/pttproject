package com.ropex.pptapp.mediasoup

import org.json.JSONObject

interface DtlsRtpCallback {
    fun onReady(dtlsJson: String, rtpJson: String)
}

class MediasoupController {
    private var nativeLoaded = false
    init {
        try {
            try { System.loadLibrary("c++_shared") } catch (_: Throwable) {}
            try { System.loadLibrary("jingle_peerconnection_so") } catch (_: Throwable) {}
            try { System.loadLibrary("webrtc") } catch (_: Throwable) {}
            try { System.loadLibrary("mediasoupclient") } catch (_: Throwable) {}
            System.loadLibrary("mediasoupbridge")
            nativeLoaded = true
        } catch (_: Throwable) {
            nativeLoaded = false
        }
    }

    var onDtlsRtpReady: ((JSONObject, JSONObject) -> Unit)? = null

    private external fun nativeInitDevice(rtpCapsJson: String)
    private external fun nativeCreateTransport(direction: String, transportId: String, iceParamsJson: String, iceCandidatesJson: String, dtlsParamsJson: String)
    private external fun nativePrepareProducer(audioTrackId: String): Boolean
    private external fun nativeSetOnDtlsRtpReadyCallback(cb: DtlsRtpCallback)

    fun setCallback(cb: (JSONObject, JSONObject) -> Unit) {
        onDtlsRtpReady = cb
        if (nativeLoaded) {
            nativeSetOnDtlsRtpReadyCallback(object : DtlsRtpCallback {
                override fun onReady(dtlsJson: String, rtpJson: String) {
                    val dtls = JSONObject(dtlsJson)
                    val rtp = JSONObject(rtpJson)
                    onDtlsRtpReady?.invoke(dtls, rtp)
                }
            })
        }
    }

    fun initDevice(rtpCapabilities: JSONObject) {
        if (nativeLoaded) {
            nativeInitDevice(rtpCapabilities.toString())
        }
    }

    fun createTransport(direction: String, transportId: String, iceParameters: JSONObject, iceCandidates: JSONObject, dtlsParameters: JSONObject) {
        if (nativeLoaded) {
            nativeCreateTransport(direction, transportId, iceParameters.toString(), iceCandidates.toString(), dtlsParameters.toString())
        }
    }

    fun prepareProducer(audioTrackId: String): Boolean {
        if (nativeLoaded) {
            return nativePrepareProducer(audioTrackId)
        }
        val dtls = JSONObject("{\"role\":\"auto\",\"fingerprints\":[{\"algorithm\":\"sha-256\",\"value\":\"00\"}]}")
        val rtp = JSONObject("{\"codecs\":[{\"mimeType\":\"audio/opus\",\"clockRate\":48000,\"channels\":1}],\"headerExtensions\":[],\"encodings\":[{\"ssrc\":1111}],\"rtcp\":{\"cname\":\"ptt\"}}")
        onDtlsRtpReady?.invoke(dtls, rtp)
        return true
    }
}
