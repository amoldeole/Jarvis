package com.jarvis.phoneguardian.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

/** Optional, transparent bridge for visible-device commands; file features do not depend on it. */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    private fun execute(intent: AssistantIntent): Boolean = when (intent) {
        AssistantIntent.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
        AssistantIntent.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
        AssistantIntent.ScrollUp -> findScrollable(rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        AssistantIntent.ScrollDown -> findScrollable(rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        AssistantIntent.ClickFirstResult -> findClickable(rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        else -> false
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (index in 0 until node.childCount) findScrollable(node.getChild(index))?.let { return it }
        return null
    }

    private fun findClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.isClickable) return node
        for (index in 0 until node.childCount) findClickable(node.getChild(index))?.let { return it }
        return null
    }

    companion object {
        private var instance: WeakReference<JarvisAccessibilityService>? = null

        fun perform(intent: AssistantIntent): Boolean = instance?.get()?.execute(intent) == true
    }
}
