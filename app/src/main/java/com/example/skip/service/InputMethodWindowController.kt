package com.example.skip.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

internal class InputMethodWindowController(
    private val service: AccessibilityService,
    private val mainHandler: Handler
) {
    private var enabledInputMethodPackages: Set<String> = emptySet()
    private var lastRefreshElapsedMillis = 0L
    private var settingsObserver: ContentObserver? = null

    fun refreshEnabledInputMethodPackages() {
        val refreshed = runCatching {
            val manager = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            buildSet {
                manager?.enabledInputMethodList
                    ?.mapNotNull { it.packageName.trim().takeIf(String::isNotBlank) }
                    ?.let(::addAll)
                Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.let(ComponentName::unflattenFromString)
                    ?.packageName
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }.getOrNull()
        if (refreshed != null) {
            enabledInputMethodPackages = refreshed
            lastRefreshElapsedMillis = SystemClock.elapsedRealtime()
        }
    }

    fun registerSettingsObserver() {
        if (settingsObserver != null) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refreshEnabledInputMethodPackages()
            }
        }
        settingsObserver = observer
        runCatching {
            service.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
                false,
                observer
            )
            service.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
                false,
                observer
            )
        }.onFailure {
            runCatching { service.contentResolver.unregisterContentObserver(observer) }
            settingsObserver = null
        }
    }

    fun unregisterSettingsObserver() {
        val observer = settingsObserver ?: return
        settingsObserver = null
        runCatching { service.contentResolver.unregisterContentObserver(observer) }
    }

    fun isInputMethodWindow(
        event: AccessibilityEvent,
        root: AccessibilityNodeInfo?,
        packageName: String
    ): Boolean {
        refreshIfStale(event.eventType)
        val isInputMethodWindowType = runCatching {
            service.windows.firstOrNull { it.id == event.windowId }?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }.getOrDefault(false)
        return InputMethodWindowPolicy.shouldBlock(
            packageName = packageName,
            eventClassName = event.className?.toString().orEmpty(),
            rootClassName = root?.className?.toString().orEmpty(),
            enabledInputMethodPackages = enabledInputMethodPackages,
            isInputMethodWindowType = isInputMethodWindowType
        )
    }

    private fun refreshIfStale(eventType: Int) {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        if (SystemClock.elapsedRealtime() - lastRefreshElapsedMillis >= REFRESH_INTERVAL_MS) {
            refreshEnabledInputMethodPackages()
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 5_000L
    }
}
