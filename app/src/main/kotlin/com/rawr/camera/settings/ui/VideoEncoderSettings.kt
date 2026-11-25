package com.rawr.camera.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawr.camera.settings.architecture.SetVideoEncoder
import com.rawr.camera.settings.architecture.SettingsDispatch
import com.rawr.camera.settings.model.KeyframeInterval
import com.rawr.camera.settings.model.SettingsUiState
import com.rawr.camera.settings.model.VideoAudioCodec
import com.rawr.camera.settings.model.VideoAudioRoute
import com.rawr.camera.settings.model.VideoAudioSource
import com.rawr.camera.settings.model.VideoBitrateMode
import com.rawr.camera.settings.model.VideoEncoderConfig
import com.rawr.camera.video.VideoEncoderCapabilities
import com.rawr.camera.video.VideoProfileFeature
import com.rawr.camera.video.VideoResolutionMode

@Composable
internal fun VideoEncoderSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val encoder = state.values.videoEncoder
    val set: (VideoEncoderConfig) -> Unit = { dispatch.invoke(SetVideoEncoder(it)) }
    val supportedModes = remember { VideoEncoderCapabilities.supportedBitrateModes }
    val maxBitrateMbps = remember { VideoEncoderCapabilities.maxBitrateMbps }
    val context = androidx.compose.ui.platform.LocalContext.current
    val unprocessedSupported = remember { com.rawr.camera.video.VideoAudioInput.supportsUnprocessed(context) }
    val bFramesProduced = remember { com.rawr.camera.video.EncoderObservations.bFramesProduced(context) }
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("VideoEncoder")) {
        VideoProfilesGroup(state, dispatch)
        VideoHoldToLockGroup()
        SettingsGroup(
            title = "Recording",
            description = "The timer's dot: green fine, amber near the limit, red frames dropped. A summary follows a take with problems."
        ) {
            SettingsSwitchRow(
                title = "Show diagnostics",
                checked = encoder.showDiagnostics,
                supportingText = "Tap the recording timer for FPS, drops, GPU time and encoder delay; " +
                    "a summary after every take"
            ) { set(encoder.copy(showDiagnostics = it)) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Mute audio",
                checked = encoder.audioMuted,
                supportingText = "Records a silent audio track (also the AUDIO button on the capture screen)"
            ) { set(encoder.copy(audioMuted = it)) }
        }
        SettingsGroup(
            title = "Horizon Lock",
            description = "Keeps the video level as you tilt (Level tile → Lock). Crops in to fill the turned frame: a wider range crops more. Default ±15°."
        ) {
            val shownRange = state.values.horizonLockRange.resolved(1f)
            com.rawr.camera.settings.model.HorizonLockRange.entries
                .filter { it != com.rawr.camera.settings.model.HorizonLockRange.Auto }
                .forEachIndexed { index, range ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = "${range.label} · %.2fx crop".format(range.crop),
                    selected = shownRange == range,
                    enabled = true
                ) { dispatch.invoke(com.rawr.camera.settings.architecture.SetHorizonLockRange(range)) }
            }
        }
        SettingsGroup {
            SettingsSwitchRow(
                title = "Sound meter",
                checked = state.values.videoAudioMeter,
                supportingText = "Two small level bars over the histogram's corner in Video",
                onCheckedChange = { dispatch.invoke(com.rawr.camera.settings.architecture.SetVideoAudioMeter(it)) }
            )
        }
        SettingsGroup(
            title = "Shutter display",
            description = "Shows the video shutter as a time (1/60) or an angle (180° = half a frame). An angle keeps its look when the fps changes."
        ) {
            listOf(false to "Time · 1/60", true to "Angle · 180°").forEachIndexed { index, (angle, title) ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = title,
                    selected = state.values.videoShutterAngle == angle,
                    enabled = true
                ) { dispatch.invoke(com.rawr.camera.settings.architecture.SetVideoShutterAngle(angle)) }
            }
        }
        SettingsGroup(
            title = "Stabilise",
            description = "Gyro stabilisation for video, inside a fixed crop: Standard ~1.15x, Strong ~1.3x for walking."
        ) {
            com.rawr.camera.settings.model.VideoStabilize.entries.forEachIndexed { index, mode ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = if (mode == com.rawr.camera.settings.model.VideoStabilize.Off) mode.label
                        else "${mode.label} · ${mode.crop}x",
                    selected = state.values.videoStabilize == mode,
                    enabled = true
                ) { dispatch.invoke(com.rawr.camera.settings.architecture.SetVideoStabilize(mode)) }
            }
        }
        SettingsGroup(
            title = "Horizon Follow",
            description = "The video follows your tilt slowly, like a gimbal: shake goes, intended turns stay smooth. Crops ~1.4x."
        ) {
            com.rawr.camera.settings.model.HorizonSmoothing.entries.forEachIndexed { index, smoothing ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = "${smoothing.label} · ${smoothing.seconds} s",
                    selected = state.values.horizonSmoothing == smoothing,
                    enabled = true
                ) { dispatch.invoke(com.rawr.camera.settings.architecture.SetHorizonSmoothing(smoothing)) }
            }
        }
        SettingsGroup(
            title = "Video",
            description = "HEVC settings apply to the next recording. Some encoders ignore B-frame limits; the recording monitor shows the actual GOP."
        ) {
            SettingsRow(
                "Bit depth"
            )
            if (state.values.videoLogEnabled) SettingsRow("10-bit", value = "Required by LOG")
            else CompactChoiceRow(listOf(10, 8), encoder.bitDepth, { "$it-bit" }) { set(encoder.copy(bitDepth = it)) }
            SettingDivider()
            SettingsRow("Bitrate mode")
            CompactChoiceRow(
                VideoBitrateMode.entries.filter { it.wireValue in supportedModes || it == encoder.bitrateMode },
                encoder.bitrateMode,
                { it.label }
            ) { set(encoder.copy(bitrateMode = it)) }
            VideoResolutionMode.entries.forEach { mode ->
                SettingDivider()
                StandaloneNumericSliderRow(
                    identity = "video_bitrate_${mode.name}",
                    label = "${mode.label} bitrate",
                    minimum = VideoEncoderConfig.MIN_BITRATE_MBPS.toFloat(),
                    maximum = maxBitrateMbps.toFloat(),
                    step = 1f,
                    decimals = 0,
                    value = encoder.bitrateMbps(mode).toFloat(),
                    defaultValue = VideoEncoderConfig().bitrateMbps(mode).toFloat(),
                    unit = " Mbps",
                    enabled = encoder.bitrateMode != VideoBitrateMode.Cq
                ) { set(encoder.withBitrateMbps(mode, it.toInt())) }
            }
            SettingDivider()
            val interval = encoder.keyframeInterval
            val frames = interval.frames(30)
            SettingsRow(
                "I-frame interval",
                value = if (frames <= 1) "Every frame"
                else "${if (interval.wireValue == 1) "0.5" else "${interval.wireValue / 2}"} s · ${interval.frames(24)} / $frames frames"
            )
            CompactChoiceRow(KeyframeInterval.entries, interval, { it.label }) {
                set(encoder.copy(keyframeInterval = it))
            }
            SettingDivider()
            if (frames <= 1) SettingsRow("Max B-frames", value = "Off with All-I")
            else {
                SettingsRow(
                    "Max B-frames",
                    value = if (encoder.maxBFrames < 0) "Auto" else "${encoder.maxBFrames}",
                    supportingText = if (bFramesProduced == false && encoder.maxBFrames != 0)
                        "This phone's encoder made no B-frames in your last recording; it records I and P frames only." else null
                )
                CompactChoiceRow(
                    VideoEncoderConfig.MAX_B_FRAME_CHOICES,
                    encoder.maxBFrames,
                    { if (it < 0) "Auto" else "$it" }
                ) { set(encoder.copy(maxBFrames = it)) }
            }
            SettingDivider()
            SettingsRow("GOP at 30 fps", value = VideoEncoderConfig.gopPattern(frames, encoder.maxBFrames))
        }
        SettingsGroup(
            title = "Audio",
            description = "48 kHz. PCM: uncompressed, in a .mov. AAC: smaller, in an .mp4. Gain is digital; the MIC lamp turns red when clipping."
        ) {
            SettingsRow("Codec", value = encoder.audioCodec.label)
            CompactChoiceRow(VideoAudioCodec.entries, encoder.audioCodec, { it.label }) {
                set(encoder.copy(audioCodec = it))
            }
            SettingDivider()
            SettingsRow("Source", value = encoder.audioSource.label)
            CompactChoiceRow(
                VideoAudioSource.entries.filter {
                    it != VideoAudioSource.Unprocessed || unprocessedSupported || it == encoder.audioSource
                },
                encoder.audioSource,
                { it.label }
            ) { set(encoder.copy(audioSource = it)) }
            SettingDivider()
            SettingsRow("Input", value = encoder.audioRoute.label)
            CompactChoiceRow(VideoAudioRoute.entries, encoder.audioRoute, { it.label }) {
                set(encoder.copy(audioRoute = it))
            }
            SettingDivider()
            StandaloneNumericSliderRow(
                identity = "video_audio_gain",
                label = "Gain",
                minimum = VideoEncoderConfig.MIN_AUDIO_GAIN_DB.toFloat(),
                maximum = VideoEncoderConfig.MAX_AUDIO_GAIN_DB.toFloat(),
                step = 1f,
                decimals = 0,
                value = encoder.audioGainDb.toFloat(),
                defaultValue = 0f,
                valueFormatter = { if (it.toInt() == 0) "0 dB" else "%+d dB".format(it.toInt()) }
            ) { set(encoder.copy(audioGainDb = it.toInt())) }
            SettingDivider()
            SettingsRow("Low cut", value = if (encoder.audioLowCutHz == 0) "Off" else "${encoder.audioLowCutHz} Hz")
            CompactChoiceRow(VideoEncoderConfig.AUDIO_LOW_CUT_HZ, encoder.audioLowCutHz, { if (it == 0) "Off" else "$it Hz" }) {
                set(encoder.copy(audioLowCutHz = it))
            }
            SettingDivider()
            SettingsRow("Channels", value = if (encoder.audioChannels == 1) "Mono" else "Stereo")
            CompactChoiceRow(listOf(2, 1), encoder.audioChannels, { if (it == 1) "Mono" else "Stereo" }) {
                set(encoder.copy(audioChannels = it))
            }
            if (!encoder.audioCodec.isPcm) {
                SettingDivider()
                SettingsRow("Bitrate", value = "${encoder.audioBitrateKbps} kbps")
                CompactChoiceRow(VideoEncoderConfig.AUDIO_BITRATES_KBPS, encoder.audioBitrateKbps, { "$it" }) {
                    set(encoder.copy(audioBitrateKbps = it))
                }
            }
        }
    }
}

/**
 * X14U (Nov 20) real-time profiles: per video mode, which processing features run while recording. A feature runs when
 * its main Video switch (method, strength) is on and its switch here is on for the current mode. Tapping a row opens
 * the feature's settings; the switch toggles it for the selected mode only.
 */
@Composable
private fun VideoProfilesGroup(state: SettingsUiState, dispatch: SettingsDispatch) {
    val v = state.values
    val profiles = com.rawr.camera.video.VideoProfiles.decode(v.videoProfiles)
    val current = com.rawr.camera.video.VideoProfileMode.of(
        com.rawr.camera.video.VideoCrop.fromId(v.videoResolutionId).size,
        com.rawr.camera.settings.model.videoFpsOrDefault(v.videoFps)
    )
    var mode by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(current) }
    val modes = com.rawr.camera.video.VideoProfileMode.entries
    SettingsGroup(
        title = "Real-time profiles",
        description = "What runs while recording, per video mode. A feature runs when it's on here and in its own " +
            "settings (tap a row). 24 fps uses the 30 fps profile. Your current mode: ${current.label}."
    ) {
        CompactSegments(modes.map { it.chip }, { modes[it] == mode }, { mode = modes[it] })
        SettingDivider()
        val rows = listOf(
            Triple(VideoProfileFeature.Highlight, v.videoHighlightEnabled, com.rawr.camera.settings.model.SettingsSection.HighlightReconstruction),
            Triple(VideoProfileFeature.Fcc, v.videoFccEnabled, com.rawr.camera.settings.model.SettingsSection.Demosaic),
            Triple(VideoProfileFeature.Defringe, v.videoDefringeEnabled, com.rawr.camera.settings.model.SettingsSection.Defringe),
            Triple(VideoProfileFeature.Denoise, v.videoDenoiseEnabled, com.rawr.camera.settings.model.SettingsSection.Denoise),
            Triple(VideoProfileFeature.Dcp, null, com.rawr.camera.settings.model.SettingsSection.Lens),
            Triple(VideoProfileFeature.LensCorrection, v.videoDistortionCorrectionEnabled, com.rawr.camera.settings.model.SettingsSection.Distortion)
        )
        val toggle: (com.rawr.camera.video.VideoProfileFeature, Boolean) -> Unit = { feature, on ->
            dispatch.invoke(com.rawr.camera.settings.architecture.SetVideoProfileFeature(mode, feature, on))
        }
        rows.forEach { (feature, mainOn, section) ->
            ToggleSubmenuRow(
                title = feature.label,
                checked = profiles.allows(mode, feature),
                supportingText = when {
                    mainOn == false -> "Off in its settings"
                    feature == VideoProfileFeature.Dcp -> "The lens's video DCP (Lens settings)"
                    else -> null
                },
                testTag = "video_profile_${feature.id}",
                onOpen = { dispatch.invoke(com.rawr.camera.settings.architecture.OpenSection(section)) },
                onCheckedChange = { toggle(feature, it) }
            )
            SettingDivider()
        }
        SettingsSwitchRow(
            title = VideoProfileFeature.Stabilise.label,
            checked = profiles.allows(mode, VideoProfileFeature.Stabilise),
            supportingText = "Stabilise below; Lock and Follow from the Level tile"
        ) { toggle(VideoProfileFeature.Stabilise, it) }
        if (mode.size != com.rawr.camera.video.VideoCropSize.HD) {
            SettingDivider()
            SettingsRow(
                mode.label,
                supportingText = "Records through the one-pass fast path while every switch here is off. " +
                    "Any switch on sends it through the full path, which may drop frames" +
                    if (mode == com.rawr.camera.video.VideoProfileMode.Uhd30) "." else " at this size."
            )
        }
        SettingDivider()
        SettingsRow(
            "Reset profiles to defaults",
            value = if (v.videoProfiles.isBlank()) "Default" else null,
            onClick = { dispatch.invoke(com.rawr.camera.settings.architecture.ResetVideoProfiles) }
        )
    }
}

/** X14U: hold the record button to record with these settings locked (released when recording stops). */
@Composable
private fun VideoHoldToLockGroup() {
    val v by com.rawr.camera.video.VideoLockSettings.values.collectAsState()
    val set: (com.rawr.camera.video.VideoLockSettings.Values) -> Unit = { com.rawr.camera.video.VideoLockSettings.update(it) }
    SettingsGroup(
        title = "Hold Shutter to Lock",
        description = "Hold the record button (or the grip's) to start recording with these locked; let go and it keeps recording. Tap to stop."
    ) {
        SettingsSwitchRow(title = "Hold to record locked", checked = v.enabled) { set(v.copy(enabled = it)) }
        SettingDivider()
        DropdownChoiceRow(
            title = "Hold time",
            selected = v.holdMs,
            values = com.rawr.camera.video.VideoLockSettings.HOLD_TIMES,
            label = { "%.1f s".format(java.util.Locale.US, it / 1000f) },
            enabled = v.enabled
        ) { set(v.copy(holdMs = it)) }
        SettingDivider()
        SettingsSwitchRow(title = "Lock shutter speed", checked = v.shutter, enabled = v.enabled) { set(v.copy(shutter = it)) }
        SettingDivider()
        SettingsSwitchRow(title = "Lock ISO", checked = v.iso, enabled = v.enabled) { set(v.copy(iso = it)) }
        SettingDivider()
        SettingsSwitchRow(title = "Lock focus", checked = v.focus, enabled = v.enabled) { set(v.copy(focus = it)) }
        SettingDivider()
        SettingsSwitchRow(title = "Lock white balance", checked = v.whiteBalance, enabled = v.enabled) {
            set(v.copy(whiteBalance = it))
        }
    }
}
