/*
 * Copyright (C) 2026 Sagar Gupta
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nativelm.app.ui.lock

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * App-lock authentication. This is a **UI gate**, not at-rest encryption: it blocks
 * casual access to a stolen/unlocked phone. The on-device database is not encrypted
 * on disk (only the HF token is, via [com.nativelm.app.data.SecureStore]); at-rest DB
 * encryption is deliberately deferred to a later release.
 *
 * No [BiometricPrompt.CryptoObject] is used — we only need a yes/no auth result, not a
 * key release — which keeps the device-credential fallback working across API levels.
 */

/**
 * Authenticators we accept: a strong biometric OR the device PIN/pattern/password.
 * The combined `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` form is only supported on
 * API 30+; on older devices we fall back to biometric-only (with a Cancel button).
 */
private fun allowedAuthenticators(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_WEAK
    }

/**
 * True if the device can satisfy an unlock prompt right now — i.e. there is an enrolled
 * biometric or (on API 30+) a device credential set. Used to enable/disable the toggle.
 */
fun canAuthenticate(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(allowedAuthenticators()) ==
        BiometricManager.BIOMETRIC_SUCCESS

/** Walk the context chain to the hosting [FragmentActivity] (required by BiometricPrompt). */
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Show the system unlock prompt. [onSuccess] fires on a successful biometric or
 * device-credential auth; [onError] fires on an unrecoverable error or user cancel
 * (the app stays locked, and the lock screen offers a retry).
 */
fun promptUnlock(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errString.toString())
            }
        },
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock NativeLM")
        .setSubtitle("Confirm it's you to open your documents and chats")
        .setAllowedAuthenticators(allowedAuthenticators())
        .apply {
            // A negative button is required only when device credential is NOT an
            // allowed authenticator; including both throws IllegalArgumentException.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                setNegativeButtonText("Cancel")
            }
        }
        .build()

    prompt.authenticate(info)
}
