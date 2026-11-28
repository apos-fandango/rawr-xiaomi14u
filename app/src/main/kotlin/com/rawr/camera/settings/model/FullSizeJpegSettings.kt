package com.rawr.camera.settings.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * X14U 50 MP chip, third stage "25MP": the lenses (by id) whose 50 MP JPEGs are scaled to half the area at the end.
 * The DNG stays 50 MP. Only offered while JPEGs are saved; kept across launches.
 */
object FullSizeJpegSettings {
    private var prefs: SharedPreferences? = null
    private val flow = MutableStateFlow<Set<String>>(emptySet())
    val halfLenses: StateFlow<Set<String>> = flow.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_fullsize_jpeg", Context.MODE_PRIVATE)
        prefs = p
        flow.value = p.getStringSet("half", emptySet()).orEmpty().toSet()
    }

    fun isHalf(lensId: String): Boolean = key(lensId) in flow.value

    fun setHalf(lensId: String, on: Boolean) {
        val k = key(lensId)
        val next = if (on) flow.value + k else flow.value - k
        if (next == flow.value) return
        flow.value = next
        prefs?.edit()?.putStringSet("half", next)?.apply()
    }

    private fun key(lensId: String) = lensId.trim().lowercase()
}
