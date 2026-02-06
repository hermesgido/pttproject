package com.ropex.pptapp.config

import android.media.AudioManager
import android.media.MediaRecorder

enum class AudioRoute { SPEAKER, EARPIECE }
enum class VolumeStream { VOICE_CALL, MUSIC }
enum class MicSource { VOICE_COMMUNICATION, MIC }

data class BeepConfig(
    val enabled: Boolean,
    val stream: VolumeStream,
    val volumeFraction: Float
)

data class PttConfig(
    val longPressLatch: Boolean,
    val longPressThresholdMs: Int
)

data class AccessoryConfig(
    val preferBluetooth: Boolean,
    val preferWiredHeadset: Boolean
)

data class AudioConfig(
    val rxRoute: AudioRoute,
    val txRoute: AudioRoute,
    val volumeStream: VolumeStream,
    val rxVolumeFraction: Float,
    val txVolumeFraction: Float,
    val micSource: MicSource,
    val hardwareAEC: Boolean,
    val hardwareNS: Boolean,
    val agcConstraint: Boolean,
    val highpassFilter: Boolean,
    val beep: BeepConfig,
    val ptt: PttConfig,
    val accessory: AccessoryConfig
)

fun VolumeStream.toAndroidStream(): Int {
    return if (this == VolumeStream.VOICE_CALL) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
}

fun MicSource.toAndroidAudioSource(): Int {
    return if (this == MicSource.VOICE_COMMUNICATION) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
}

