package com.ropex.pptapp

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.ropex.pptapp.config.VolumeStream

class PTTManager(private val context: Context) {

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }


    private var beepStream: Int = AudioManager.STREAM_MUSIC
    private var beepVolume: Int = 100
    private var talkPermitEnabled: Boolean = true
    private var toneGenerator: ToneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    // Use StateFlow instead of LiveData
    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting

    private val _currentSpeaker = MutableStateFlow<String?>(null)
    val currentSpeaker: StateFlow<String?> = _currentSpeaker

    private val _channelUsers = MutableStateFlow<List<String>>(emptyList())
    val channelUsers: StateFlow<List<String>> = _channelUsers

    private var currentChannelId: String? = null
    private var currentUserId: String? = null

    // Audio output modes
    enum class AudioOutput {
        SPEAKER, EARPIECE, HEADSET
    }

    var audioOutput = AudioOutput.SPEAKER
        set(value) {
            field = value
            updateAudioRouting()
        }

    fun onPTTPressed() {
        Log.d("PTT", "PTT button pressed")

        if (currentChannelId == null || currentUserId == null) {
            Log.w("PTT", "Not in a channel")
            playErrorTone()
            return
        }

        // Notify signaling server to request speak
        // This will be connected in MainActivity
        _isTransmitting.value = true
        playStartTone()
    }

    fun onPTTReleased() {
        Log.d("PTT", "PTT button released")

        if (!_isTransmitting.value) return

        // Notify signaling server to stop speaking
        _isTransmitting.value = false
        playStopTone()
    }

    fun setCurrentSpeaker(userId: String?, userName: String?) {
        _currentSpeaker.value = if (userId != null) {
            "$userName ($userId)"
        } else {
            null
        }
    }

    fun joinChannel(channelId: String, userId: String) {
        currentChannelId = channelId
        currentUserId = userId
        Log.d("PTT", "Joined channel: $channelId as user: $userId")
    }

    fun leaveChannel() {
        currentChannelId = null
        currentUserId = null
        _isTransmitting.value = false
        _currentSpeaker.value = null
        Log.d("PTT", "Left channel")
    }

    private fun updateAudioRouting() {
        when (audioOutput) {
            AudioOutput.SPEAKER -> {
                audioManager.setSpeakerphoneOn(true)
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            AudioOutput.EARPIECE -> {
                audioManager.setSpeakerphoneOn(false)
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            AudioOutput.HEADSET -> {
                audioManager.setSpeakerphoneOn(false)
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                // Headset routing is automatic
            }

        }
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
    }

    private fun playStartTone() {
        if (!talkPermitEnabled) return
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
    }

    private fun playStopTone() {
        if (!talkPermitEnabled) return
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 100)
    }

    private fun playBusyTone() {
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_NETWORK_BUSY, 500)
    }

    private fun playErrorTone() {
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ABBR_INTERCEPT, 300)
    }

    fun release() {
        toneGenerator.release()
    }

    fun setBeepConfig(stream: VolumeStream, fraction: Float, enabled: Boolean) {
        val s = if (stream == VolumeStream.VOICE_CALL) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        beepStream = s
        beepVolume = kotlin.math.max(1, kotlin.math.min(100, (fraction * 100f).toInt()))
        talkPermitEnabled = enabled
        toneGenerator.release()
        toneGenerator = ToneGenerator(beepStream, beepVolume)
    }
}
