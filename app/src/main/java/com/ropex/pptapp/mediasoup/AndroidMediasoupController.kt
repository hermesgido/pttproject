package com.ropex.pptapp.mediasoup

import android.content.Context
import org.json.JSONObject
import org.mediasoup.droid.Device
import org.mediasoup.droid.MediasoupClient
import org.mediasoup.droid.Producer
import org.mediasoup.droid.SendTransport
import org.mediasoup.droid.RecvTransport
import org.mediasoup.droid.Consumer
import org.webrtc.AudioTrack
import com.ropex.pptapp.signaling.SignalingClient

class AndroidMediasoupController(
    private val signalingClient: SignalingClient
) {
    private var device: Device? = null
    private var deviceLoaded: Boolean = false
    private var sendTransport: SendTransport? = null
    private var recvTransport: RecvTransport? = null
    private var audioProducer: Producer? = null
    private val consumers = mutableListOf<Consumer>()

    fun initialize(context: Context) {
        MediasoupClient.initialize(context)
        device = Device()
    }

    fun loadDevice(rtpCapabilities: JSONObject) {
        device?.load(rtpCapabilities.toString(), null)
        deviceLoaded = true
    }

    fun createSendTransport(info: JSONObject) {
        val id = info.getString("id")
        val iceParameters = info.getJSONObject("iceParameters").toString()
        val iceCandidates = info.getJSONArray("iceCandidates").toString()
        val dtlsParameters = info.getJSONObject("dtlsParameters").toString()
        sendTransport = device?.createSendTransport(object : SendTransport.Listener {
            override fun onConnect(transport: org.mediasoup.droid.Transport?, dtlsParameters: String?) {
                val tId = sendTransport?.id ?: id
                if (dtlsParameters != null) signalingClient.connectTransport(tId, JSONObject(dtlsParameters))
            }
            override fun onProduce(transport: org.mediasoup.droid.Transport?, kind: String?, rtpParameters: String?, appData: String?): String {
                val tId = sendTransport?.id ?: id
                if (rtpParameters != null) signalingClient.produceAudio(tId, JSONObject(rtpParameters))
                return ""
            }
            override fun onProduceData(transport: org.mediasoup.droid.Transport?, sctpStreamParameters: String?, label: String?, protocol: String?, appData: String?): String {
                return ""
            }
            override fun onConnectionStateChange(transport: org.mediasoup.droid.Transport?, connectionState: String?) {}
        }, id, iceParameters, iceCandidates, dtlsParameters)
    }

    fun createRecvTransport(info: JSONObject) {
        val id = info.getString("id")
        val iceParameters = info.getJSONObject("iceParameters").toString()
        val iceCandidates = info.getJSONArray("iceCandidates").toString()
        val dtlsParameters = info.getJSONObject("dtlsParameters").toString()
        recvTransport = device?.createRecvTransport(object : RecvTransport.Listener {
            override fun onConnect(transport: org.mediasoup.droid.Transport?, dtlsParameters: String?) {
                val tId = recvTransport?.id ?: id
                if (dtlsParameters != null) signalingClient.connectTransport(tId, JSONObject(dtlsParameters))
            }
            override fun onConnectionStateChange(transport: org.mediasoup.droid.Transport?, connectionState: String?) {}
        }, id, iceParameters, iceCandidates, dtlsParameters)
    }

    fun produceAudio(track: AudioTrack) {
        val transport = sendTransport ?: return
        audioProducer = transport.produce({ }, track, null, null, null)
    }

    fun onConsumerCreated(data: JSONObject) {
        val id = data.getString("id")
        val producerId = data.getString("producerId")
        val kind = data.getString("kind")
        val rtpParameters = data.getJSONObject("rtpParameters").toString()
        val consumer = recvTransport?.consume({ }, id, producerId, kind, rtpParameters, "{}")
        if (consumer != null) {
            consumers.add(consumer)
            val track = consumer.track
            if (track is AudioTrack) {
                track.setEnabled(true)
            }
        }
    }

    fun isDeviceLoaded(): Boolean = deviceLoaded
}
