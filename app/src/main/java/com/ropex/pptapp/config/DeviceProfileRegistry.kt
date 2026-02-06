package com.ropex.pptapp.config

import android.os.Build

object DeviceProfileRegistry {
    fun defaultConfig(): AudioConfig {
        return AudioConfig(
            rxRoute = AudioRoute.SPEAKER,
            txRoute = AudioRoute.EARPIECE,
            volumeStream = VolumeStream.VOICE_CALL,
            rxVolumeFraction = 0.9f,
            txVolumeFraction = 0.8f,
            micSource = MicSource.VOICE_COMMUNICATION,
            hardwareAEC = false,
            hardwareNS = true,
            agcConstraint = false,
            highpassFilter = true,
            beep = BeepConfig(enabled = true, stream = VolumeStream.MUSIC, volumeFraction = 1.0f),
            ptt = PttConfig(longPressLatch = false, longPressThresholdMs = 350),
            accessory = AccessoryConfig(preferBluetooth = false, preferWiredHeadset = false)
        )
    }

    fun profile(name: String): AudioConfig {
        return when (name.lowercase()) {
            "telox" -> AudioConfig(
                rxRoute = AudioRoute.SPEAKER,
                txRoute = AudioRoute.EARPIECE,
                volumeStream = VolumeStream.VOICE_CALL,
                rxVolumeFraction = 0.9f,
                txVolumeFraction = 0.8f,
                micSource = MicSource.VOICE_COMMUNICATION,
                hardwareAEC = true,
                hardwareNS = true,
                agcConstraint = false,
                highpassFilter = true,
                beep = BeepConfig(enabled = true, stream = VolumeStream.VOICE_CALL, volumeFraction = 0.9f),
                ptt = PttConfig(longPressLatch = true, longPressThresholdMs = 400),
                accessory = AccessoryConfig(preferBluetooth = false, preferWiredHeadset = true)
            )
            "boxchip" -> AudioConfig(
                rxRoute = AudioRoute.SPEAKER,
                txRoute = AudioRoute.EARPIECE,
                volumeStream = VolumeStream.VOICE_CALL,
                rxVolumeFraction = 0.9f,
                txVolumeFraction = 0.8f,
                micSource = MicSource.MIC,
                hardwareAEC = false,
                hardwareNS = true,
                agcConstraint = false,
                highpassFilter = true,
                beep = BeepConfig(enabled = true, stream = VolumeStream.VOICE_CALL, volumeFraction = 0.9f),
                ptt = PttConfig(longPressLatch = false, longPressThresholdMs = 350),
                accessory = AccessoryConfig(preferBluetooth = false, preferWiredHeadset = true)
            )
            else -> defaultConfig()
        }
    }

    fun autoDetect(): AudioConfig {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("telox") -> profile("telox")
            m.contains("boxchip") -> profile("boxchip")
            else -> defaultConfig()
        }
    }
}

