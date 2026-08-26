package com.jarvis.phoneguardian.core.mirroring

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

/**
 * Permission boundary for future browser mirroring. MediaProjection is intentionally user-consent
 * driven; there is no hidden capture and no claim of remote control without Accessibility access.
 */
class ScreenMirrorController(context: Context) {
    private val manager = context.getSystemService(MediaProjectionManager::class.java)

    fun isAvailable(): Boolean = manager != null
    fun createConsentIntent(): Intent? = manager?.createScreenCaptureIntent()

    fun describeResult(activity: Activity, resultCode: Int): String = if (resultCode == Activity.RESULT_OK) {
        "Screen capture was approved for this session. Start a WebRTC sink explicitly before streaming."
    } else {
        "Screen capture was not approved; nothing was captured."
    }
}
