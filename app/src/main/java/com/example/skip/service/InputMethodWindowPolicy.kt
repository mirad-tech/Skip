package com.example.skip.service

object InputMethodWindowPolicy {
    const val SOFT_INPUT_WINDOW_CLASS = "android.inputmethodservice.SoftInputWindow"
    const val BLOCKED_REASON = "input_method_window"

    fun shouldBlock(
        packageName: String,
        eventClassName: String,
        rootClassName: String,
        enabledInputMethodPackages: Set<String>,
        isInputMethodWindowType: Boolean = false
    ): Boolean = isInputMethodWindowType ||
        eventClassName == SOFT_INPUT_WINDOW_CLASS ||
        rootClassName == SOFT_INPUT_WINDOW_CLASS ||
        packageName in enabledInputMethodPackages
}
