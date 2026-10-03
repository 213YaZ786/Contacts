package com.yaz.contacts.core.security

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Contacts behind the phone's own lock: a fingerprint, the face or the
 * phone's PIN, asked by Android's own prompt (the app never sees any of
 * them), when the app comes back after a while away. Held in memory only:
 * a new process starts locked.
 */
object AppLock {

    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    private var leftAt = 0L

    /** The app left the screen: the time starts. */
    fun leave() {
        if (_open.value) leftAt = SystemClock.elapsedRealtime()
    }

    /** Coming back: still open within [graceMs] of leaving, else locked again. */
    fun back(graceMs: Long) {
        if (_open.value && SystemClock.elapsedRealtime() - leftAt > graceMs) _open.value = false
    }

    /**
     * Whether the phone itself has a lock to ask: without a screen lock
     * there is nothing to unlock with, and the app must never shut its
     * owner out.
     */
    fun possible(context: android.content.Context): Boolean = runCatching {
        context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            ?.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    fun ask(activity: Activity, onFail: () -> Unit = {}) {
        if (_open.value) return
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock Contacts")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        runCatching {
            prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _open.value = true
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail()
            })
        }.onFailure { onFail() }
    }
}
