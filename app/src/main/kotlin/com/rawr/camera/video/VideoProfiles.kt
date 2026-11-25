package com.rawr.camera.video

/**
 * X14U (Nov 20) real-time video profiles: which video processing features run at each size and frame rate. A feature
 * runs only when its main Video switch is on (method and strength live there) and its switch for the current mode is
 * on here. Edited in Settings → Video Encoder → Real-time profiles; saved with the other settings.
 *
 * Defaults, from takes on the phone (frame time per budget):
 * - 1080p30 (~15 of 33 ms base): everything but denoise
 * - 1080p60 (~11 of 16.7 ms base, ~13 with highlight): highlight reconstruction, DCP, lens correction, stabilisation
 * - 4K30 (~28 of 33 ms base): lens correction and stabilisation only
 * - Open Gate 30 (4096x2304, ~34 of 33.3 ms base: the full path misses even with every feature off) and 4K60:
 *   nothing; they record through the one-pass fast path, which can't do any of them
 * - denoise: nowhere (~110 ms a frame even at 1080p)
 */
enum class VideoProfileMode(val id: String, val label: String, val chip: String, val size: VideoCropSize) {
    Hd30("hd30", "1080p 30 fps", "1080·30", VideoCropSize.HD),
    Hd60("hd60", "1080p 60 fps", "1080·60", VideoCropSize.HD),
    Uhd30("uhd30", "4K 30 fps", "4K·30", VideoCropSize.UHD),
    Uhd60("uhd60", "4K 60 fps", "4K·60", VideoCropSize.UHD),
    OpenGate30("og30", "Open Gate 30 fps", "OG·30", VideoCropSize.OpenGate);

    companion object {
        /** 24 fps uses the 30 fps profile (more time per frame). */
        fun of(size: VideoCropSize, fps: Int): VideoProfileMode = when (size) {
            VideoCropSize.HD -> if (fps > 30) Hd60 else Hd30
            VideoCropSize.UHD -> if (fps > 30) Uhd60 else Uhd30
            VideoCropSize.OpenGate -> OpenGate30
        }

        fun fromId(id: String): VideoProfileMode? = entries.firstOrNull { it.id == id }
    }
}

enum class VideoProfileFeature(val id: String, val label: String) {
    Highlight("hl", "Highlight reconstruction"),
    Fcc("fcc", "FCC"),
    Defringe("df", "Defringe"),
    Denoise("dn", "Denoise"),
    Dcp("dcp", "DCP"),
    LensCorrection("lens", "Lens correction"),
    Stabilise("stab", "Stabilise / Horizon lock");

    companion object {
        fun fromId(id: String): VideoProfileFeature? = entries.firstOrNull { it.id == id }
    }
}

data class VideoProfiles(val enabled: Map<VideoProfileMode, Set<VideoProfileFeature>>) {
    fun allows(mode: VideoProfileMode, feature: VideoProfileFeature): Boolean =
        feature in (enabled[mode] ?: DEFAULT.enabled[mode].orEmpty())

    fun with(mode: VideoProfileMode, feature: VideoProfileFeature, on: Boolean): VideoProfiles {
        val current = enabled[mode] ?: DEFAULT.enabled[mode].orEmpty()
        return VideoProfiles(enabled + (mode to (if (on) current + feature else current - feature)))
    }

    /** "hd30:hl,fcc,df;hd60:hl;..." — every mode written out, so later default changes don't move saved choices. */
    fun encode(): String = VideoProfileMode.entries.joinToString(";") { mode ->
        mode.id + ":" + VideoProfileFeature.entries.filter { allows(mode, it) }.joinToString(",") { it.id }
    }

    companion object {
        val DEFAULT = VideoProfiles(
            mapOf(
                VideoProfileMode.Hd30 to setOf(
                    VideoProfileFeature.Highlight, VideoProfileFeature.Fcc, VideoProfileFeature.Defringe,
                    VideoProfileFeature.Dcp, VideoProfileFeature.LensCorrection, VideoProfileFeature.Stabilise
                ),
                VideoProfileMode.Hd60 to setOf(
                    VideoProfileFeature.Highlight, VideoProfileFeature.Dcp, VideoProfileFeature.LensCorrection,
                    VideoProfileFeature.Stabilise
                ),
                VideoProfileMode.Uhd30 to setOf(VideoProfileFeature.LensCorrection, VideoProfileFeature.Stabilise),
                VideoProfileMode.Uhd60 to emptySet(),
                VideoProfileMode.OpenGate30 to emptySet()
            )
        )

        /** Blank or unreadable: the defaults; a mode missing from the text keeps its default. */
        fun decode(text: String?): VideoProfiles {
            if (text.isNullOrBlank()) return DEFAULT
            val parsed = mutableMapOf<VideoProfileMode, Set<VideoProfileFeature>>()
            for (part in text.split(';')) {
                val mode = VideoProfileMode.fromId(part.substringBefore(':', "")) ?: continue
                parsed[mode] = part.substringAfter(':', "").split(',')
                    .mapNotNull { VideoProfileFeature.fromId(it.trim()) }.toSet()
            }
            return VideoProfiles(DEFAULT.enabled + parsed)
        }
    }
}
