package com.rawr.camera.settings.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * X14U LUT strength (Look > Profile > STRENGTH): how much of each imported LUT is mixed over RAWR NTRL,
 * kept per LUT profile id (0..1, 1 = the LUT alone). Kept across launches.
 */
object LutStrengthSettings {
    private var prefs: SharedPreferences? = null
    private val flow = MutableStateFlow<Map<String, Float>>(emptyMap())
    val strengths: StateFlow<Map<String, Float>> = flow.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_lut_strength", Context.MODE_PRIVATE)
        prefs = p
        flow.value = p.all.mapNotNull { (k, v) -> (v as? Float)?.let { k to it.coerceIn(0f, 1f) } }.toMap()
    }

    fun strengthFor(profileId: String?): Float = profileId?.let { flow.value[it] } ?: 1f

    fun set(profileId: String, strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        if (strengthFor(profileId) == s) return
        flow.value = flow.value + (profileId to s)
        prefs?.edit()?.putFloat(profileId, s)?.apply()
    }
}

/** The imported LUT the current mode renders through, or null (RAWR NTRL, AgX, sRGB, LOG). */
fun SettingsValues.activeUserLutId(): String? =
    if (!isLogActive && regularRenderProfile() == ColorRenderProfile.UserLut) regularLutProfileId() else null
