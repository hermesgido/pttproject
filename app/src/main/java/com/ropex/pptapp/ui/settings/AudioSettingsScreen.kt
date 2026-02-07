package com.ropex.pptapp.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ropex.pptapp.config.AudioConfig
import com.ropex.pptapp.config.AudioRoute
import com.ropex.pptapp.config.VolumeStream
import com.ropex.pptapp.config.MicSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsScreen(
    audioConfig: AudioConfig,
    isTransmitting: Boolean,
    onBack: () -> Unit,
    onResetDefaults: () -> Unit,
    onChangeRxRoute: (AudioRoute) -> Unit,
    onChangeTxRoute: (AudioRoute) -> Unit,
    onChangeVolumeStream: (VolumeStream) -> Unit,
    onChangeRxVolume: (Float) -> Unit,
    onChangeTxVolume: (Float) -> Unit,
    onChangeMicSource: (MicSource) -> Unit,
    onChangeAEC: (Boolean) -> Unit,
    onChangeNS: (Boolean) -> Unit,
    onChangeAGC: (Boolean) -> Unit,
    onChangeHighpass: (Boolean) -> Unit,
    onChangeBeepEnabled: (Boolean) -> Unit,
    onChangeBeepStream: (VolumeStream) -> Unit,
    onChangeBeepVolume: (Float) -> Unit,
    onChangeLatch: (Boolean) -> Unit,
    onChangeThreshold: (Int) -> Unit,
    onChangePreferBluetooth: (Boolean) -> Unit,
    onChangePreferWired: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio & PTT Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Audio Routing Section
            item {
                SettingsSection(title = "Audio Routing") {
                    SettingItem(label = "Receive Audio") {
                        SegmentedButtonRow {
                            SegmentedButton(
                                selected = audioConfig.rxRoute == AudioRoute.SPEAKER,
                                onClick = { onChangeRxRoute(AudioRoute.SPEAKER) },
                                label = "Speaker"
                            )
                            SegmentedButton(
                                selected = audioConfig.rxRoute == AudioRoute.EARPIECE,
                                onClick = { onChangeRxRoute(AudioRoute.EARPIECE) },
                                label = "Earpiece"
                            )
                        }
                    }
                    
                    SettingItem(label = "Transmit Audio") {
                        SegmentedButtonRow {
                            SegmentedButton(
                                selected = audioConfig.txRoute == AudioRoute.SPEAKER,
                                onClick = { onChangeTxRoute(AudioRoute.SPEAKER) },
                                label = "Speaker"
                            )
                            SegmentedButton(
                                selected = audioConfig.txRoute == AudioRoute.EARPIECE,
                                onClick = { onChangeTxRoute(AudioRoute.EARPIECE) },
                                label = "Earpiece"
                            )
                        }
                    }
                }
            }

            // Volume Section
            item {
                SettingsSection(title = "Volume") {
                    SettingItem(label = "Volume Stream") {
                        SegmentedButtonRow {
                            SegmentedButton(
                                selected = audioConfig.volumeStream == VolumeStream.VOICE_CALL,
                                onClick = { onChangeVolumeStream(VolumeStream.VOICE_CALL) },
                                label = "Voice Call"
                            )
                            SegmentedButton(
                                selected = audioConfig.volumeStream == VolumeStream.MUSIC,
                                onClick = { onChangeVolumeStream(VolumeStream.MUSIC) },
                                label = "Music"
                            )
                        }
                    }
                    
                    SliderSetting(
                        label = "Receive Volume",
                        value = audioConfig.rxVolumeFraction,
                        onValueChange = onChangeRxVolume,
                        valueRange = 0.1f..1.0f
                    )
                    
                    SliderSetting(
                        label = "Transmit Volume",
                        value = audioConfig.txVolumeFraction,
                        onValueChange = onChangeTxVolume,
                        valueRange = 0.1f..1.0f
                    )
                }
            }

            // Microphone Section
            item {
                SettingsSection(title = "Microphone") {
                    SettingItem(label = "Audio Source") {
                        SegmentedButtonRow {
                            SegmentedButton(
                                selected = audioConfig.micSource == MicSource.VOICE_COMMUNICATION,
                                onClick = { onChangeMicSource(MicSource.VOICE_COMMUNICATION) },
                                label = "Voice Comm"
                            )
                            SegmentedButton(
                                selected = audioConfig.micSource == MicSource.MIC,
                                onClick = { onChangeMicSource(MicSource.MIC) },
                                label = "Mic"
                            )
                        }
                    }
                    
                    SwitchSetting(
                        label = "Hardware AEC",
                        description = "Acoustic Echo Cancellation",
                        checked = audioConfig.hardwareAEC,
                        onCheckedChange = onChangeAEC
                    )
                    
                    SwitchSetting(
                        label = "Hardware NS",
                        description = "Noise Suppression",
                        checked = audioConfig.hardwareNS,
                        onCheckedChange = onChangeNS
                    )
                    
                    SwitchSetting(
                        label = "Automatic Gain Control",
                        checked = audioConfig.agcConstraint,
                        onCheckedChange = onChangeAGC
                    )
                    
                    SwitchSetting(
                        label = "Highpass Filter",
                        checked = audioConfig.highpassFilter,
                        onCheckedChange = onChangeHighpass
                    )
                }
            }

            // Beep Settings Section
            item {
                SettingsSection(title = "Beep Tones") {
                    SwitchSetting(
                        label = "Talk Permit Tone",
                        checked = audioConfig.beep.enabled,
                        onCheckedChange = onChangeBeepEnabled
                    )
                    
                    if (audioConfig.beep.enabled) {
                        SettingItem(label = "Beep Stream") {
                            SegmentedButtonRow {
                                SegmentedButton(
                                    selected = audioConfig.beep.stream == VolumeStream.VOICE_CALL,
                                    onClick = { onChangeBeepStream(VolumeStream.VOICE_CALL) },
                                    label = "Voice Call"
                                )
                                SegmentedButton(
                                    selected = audioConfig.beep.stream == VolumeStream.MUSIC,
                                    onClick = { onChangeBeepStream(VolumeStream.MUSIC) },
                                    label = "Music"
                                )
                            }
                        }
                        
                        SliderSetting(
                            label = "Beep Volume",
                            value = audioConfig.beep.volumeFraction,
                            onValueChange = onChangeBeepVolume,
                            valueRange = 0.1f..1.0f
                        )
                    }
                }
            }

            // PTT Section
            item {
                SettingsSection(title = "Push-to-Talk") {
                    SwitchSetting(
                        label = "Long-Press Latch",
                        description = "Hold to lock transmission",
                        checked = audioConfig.ptt.longPressLatch,
                        onCheckedChange = onChangeLatch
                    )
                    
                    if (audioConfig.ptt.longPressLatch) {
                        SliderSetting(
                            label = "Latch Threshold",
                            value = audioConfig.ptt.longPressThresholdMs.toFloat(),
                            onValueChange = { onChangeThreshold(it.toInt()) },
                            valueRange = 200f..1000f,
                            valueFormatter = { "${it.toInt()} ms" }
                        )
                    }
                }
            }

            // Accessories Section
            item {
                SettingsSection(title = "Accessories") {
                    SwitchSetting(
                        label = "Prefer Bluetooth",
                        description = "Auto-route to Bluetooth when available",
                        checked = audioConfig.accessory.preferBluetooth,
                        onCheckedChange = onChangePreferBluetooth
                    )
                    
                    SwitchSetting(
                        label = "Prefer Wired Headset",
                        description = "Auto-route to wired headset when available",
                        checked = audioConfig.accessory.preferWiredHeadset,
                        onCheckedChange = onChangePreferWired
                    )
                }
            }

            // Reset Button
            item {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onResetDefaults,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset to Defaults")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// Composable Components

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun SettingItem(
    label: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun SegmentedButtonRow(
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        content()
    }
}

@Composable
private fun RowScope.SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.weight(1f)
    )
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueFormatter: (Float) -> String = { "${(it * 100).toInt()}%" }
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
