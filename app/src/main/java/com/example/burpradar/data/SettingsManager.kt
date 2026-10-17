package com.example.burpradar.data

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("burp_radar_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CONFIDENCE_THRESHOLD = "confidence_threshold"
        private const val KEY_CLIP_DURATION_MS = "clip_duration_ms"
        private const val KEY_MAX_CLIPS = "max_clips"
        private const val KEY_PREVIEW_ENABLED = "preview_enabled"
        private const val KEY_LENS_FACING = "lens_facing"

        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.40f
        const val DEFAULT_CLIP_DURATION_MS = 5000L
        const val DEFAULT_MAX_CLIPS = 50
        const val DEFAULT_PREVIEW_ENABLED = true
        const val DEFAULT_LENS_FACING = 1 // CameraSelector.LENS_FACING_BACK
    }

    var confidenceThreshold: Float
        get() = prefs.getFloat(KEY_CONFIDENCE_THRESHOLD, DEFAULT_CONFIDENCE_THRESHOLD)
        set(value) = prefs.edit().putFloat(KEY_CONFIDENCE_THRESHOLD, value).apply()

    var clipDurationMs: Long
        get() = prefs.getLong(KEY_CLIP_DURATION_MS, DEFAULT_CLIP_DURATION_MS)
        set(value) = prefs.edit().putLong(KEY_CLIP_DURATION_MS, value).apply()

    var maxClips: Int
        get() = prefs.getInt(KEY_MAX_CLIPS, DEFAULT_MAX_CLIPS)
        set(value) = prefs.edit().putInt(KEY_MAX_CLIPS, value).apply()

    var previewEnabled: Boolean
        get() = prefs.getBoolean(KEY_PREVIEW_ENABLED, DEFAULT_PREVIEW_ENABLED)
        set(value) = prefs.edit().putBoolean(KEY_PREVIEW_ENABLED, value).apply()

    var lensFacing: Int
        get() = prefs.getInt(KEY_LENS_FACING, DEFAULT_LENS_FACING)
        set(value) = prefs.edit().putInt(KEY_LENS_FACING, value).apply()
}
