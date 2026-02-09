package com.ropex.pptapp.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import android.view.KeyEvent
import com.ropex.pptapp.config.AudioConfig
import com.ropex.pptapp.config.AudioRoute
import com.ropex.pptapp.config.VolumeStream
import com.ropex.pptapp.config.MicSource
import kotlinx.coroutines.launch

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
        val listState = rememberLazyListState()
        var volumeFocus by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()
        var pendingFocusSectionIndex by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(Unit) { pendingFocusSectionIndex = 0 }
        val focusManager = LocalFocusManager.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .onPreviewKeyEvent {
                    val action = it.nativeKeyEvent.action
                    val keyCode = it.nativeKeyEvent.keyCode
                    if (action == KeyEvent.ACTION_DOWN) {
                        val current = listState.firstVisibleItemIndex
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                val moved = focusManager.moveFocus(FocusDirection.Up)
                                if (!moved) {
                                    val target = maxOf(0, current - 1)
                                    pendingFocusSectionIndex = target
                                    scope.launch { listState.animateScrollToItem(target) }
                                }
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val moved = focusManager.moveFocus(FocusDirection.Down)
                                if (!moved) {
                                    val target = current + 1
                                    pendingFocusSectionIndex = target
                                    scope.launch { listState.animateScrollToItem(target) }
                                }
                                true
                            }
                            else -> false
                        }
                    } else false
                },
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Audio Routing Section
            item {
                SettingsSection(title = "Audio Routing") {
                    val shouldFocusRouting = pendingFocusSectionIndex == 0
                    LaunchedEffect(shouldFocusRouting) {
                        if (shouldFocusRouting) pendingFocusSectionIndex = null
                    }
                    SettingItem(label = "Receive Audio") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioRow(
                                text = "Speaker",
                                selected = audioConfig.rxRoute == AudioRoute.SPEAKER,
                                onSelect = { onChangeRxRoute(AudioRoute.SPEAKER) },
                                requestFocus = shouldFocusRouting
                            )
                            RadioRow(
                                text = "Earpiece",
                                selected = audioConfig.rxRoute == AudioRoute.EARPIECE,
                                onSelect = { onChangeRxRoute(AudioRoute.EARPIECE) }
                            )
                        }
                    }
                    
                    SettingItem(label = "Transmit Audio") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioRow(
                                text = "Speaker",
                                selected = audioConfig.txRoute == AudioRoute.SPEAKER,
                                onSelect = { onChangeTxRoute(AudioRoute.SPEAKER) }
                            )
                            RadioRow(
                                text = "Earpiece",
                                selected = audioConfig.txRoute == AudioRoute.EARPIECE,
                                onSelect = { onChangeTxRoute(AudioRoute.EARPIECE) }
                            )
                        }
                    }
                }
            }

            // Volume Section
            item {
                SettingsSection(title = "Volume") {
                    SettingItem(label = "Volume Stream") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioRow(
                                text = "Voice Call",
                                selected = audioConfig.volumeStream == VolumeStream.VOICE_CALL,
                                onSelect = { onChangeVolumeStream(VolumeStream.VOICE_CALL) }
                            )
                            RadioRow(
                                text = "Music",
                                selected = audioConfig.volumeStream == VolumeStream.MUSIC,
                                onSelect = { onChangeVolumeStream(VolumeStream.MUSIC) }
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
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RadioRow(
                                text = "Voice Comm",
                                selected = audioConfig.micSource == MicSource.VOICE_COMMUNICATION,
                                onSelect = { onChangeMicSource(MicSource.VOICE_COMMUNICATION) }
                            )
                            RadioRow(
                                text = "Mic",
                                selected = audioConfig.micSource == MicSource.MIC,
                                onSelect = { onChangeMicSource(MicSource.MIC) }
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
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                RadioRow(
                                    text = "Voice Call",
                                    selected = audioConfig.beep.stream == VolumeStream.VOICE_CALL,
                                    onSelect = { onChangeBeepStream(VolumeStream.VOICE_CALL) }
                                )
                                RadioRow(
                                    text = "Music",
                                    selected = audioConfig.beep.stream == VolumeStream.MUSIC,
                                    onSelect = { onChangeBeepStream(VolumeStream.MUSIC) }
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
private fun RadioRow(
    text: String,
    selected: Boolean,
    onSelect: () -> Unit,
    requestFocus: Boolean = false
) {
    val fr = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }
    LaunchedEffect(requestFocus) {
        if (requestFocus) fr.requestFocus()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fr)
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent {
                val action = it.nativeKeyEvent.action
                val keyCode = it.nativeKeyEvent.keyCode
                if (action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onSelect()
                    true
                } else false
            }
            .then(
                if (isFocused) Modifier
                    .background(Color.Red, RoundedCornerShape(8.dp))
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            )
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .onKeyEvent {
                val action = it.nativeKeyEvent.action
                val keyCode = it.nativeKeyEvent.keyCode
                if (action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onCheckedChange(!checked)
                    true
                } else false
            }
            .then(
                if (isFocused) Modifier
                    .background(Color.Red, RoundedCornerShape(8.dp))
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            )
            .padding(4.dp),
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
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var isFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusable()
                .onFocusChanged { isFocused = it.isFocused }
                .onKeyEvent {
                    val action = it.nativeKeyEvent.action
                    val keyCode = it.nativeKeyEvent.keyCode
                    if (action == KeyEvent.ACTION_DOWN) {
                        val step = 0.05f
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val nv = (value - step).coerceIn(valueRange.start, valueRange.endInclusive)
                                onValueChange(nv)
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val nv = (value + step).coerceIn(valueRange.start, valueRange.endInclusive)
                                onValueChange(nv)
                                true
                            }
                            else -> false
                        }
                    } else false
                }
                .then(
                    if (isFocused) Modifier
                        .background(Color.Red, RoundedCornerShape(8.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    else Modifier
                )
                .padding(4.dp),
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
            modifier = Modifier
                .fillMaxWidth()
                .focusable(false)
        )
    }
}
