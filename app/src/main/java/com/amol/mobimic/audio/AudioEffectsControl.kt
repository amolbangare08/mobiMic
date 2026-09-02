package com.amol.mobimic.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Belt and braces on top of the Unprocessed input preset.
 *
 * Some devices attach AGC, noise suppression or echo cancellation to a recording
 * session regardless of the preset. Attaching to the session ourselves and calling
 * setEnabled(false) is the only way to be sure they are off, and the only way to
 * report honestly when they cannot be turned off.
 *
 * The effect objects must be retained: releasing them detaches our override.
 */
object AudioEffectsControl {

    enum class State {
        /** The platform does not offer this effect - nothing to disable. */
        UNAVAILABLE,

        /** Attached and confirmed off. */
        DISABLED,

        /** Attached but the platform refused to turn it off. Audio is being processed. */
        STUCK_ON,

        /** Could not attach. Unknown whether it is running. */
        FAILED,

        /** Not attached by choice: no session id was allocated, to keep the MMAP path. */
        SKIPPED,
    }

    data class Report(
        val agc: State = State.UNAVAILABLE,
        val noiseSuppressor: State = State.UNAVAILABLE,
        val echoCanceler: State = State.UNAVAILABLE,
    ) {
        val allClear: Boolean
            get() = listOf(agc, noiseSuppressor, echoCanceler)
                .none { it == State.STUCK_ON || it == State.FAILED }
    }

    private const val TAG = "mobiMic"
    private val held = mutableListOf<AudioEffect>()

    /** Reports every effect as skipped, for the no-session low-latency path. */
    fun skipped(): Report = Report(State.SKIPPED, State.SKIPPED, State.SKIPPED)

    @Synchronized
    fun disableAll(sessionId: Int): Report {
        release()
        if (sessionId <= 0) return Report()

        val agc = disable("AGC", AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)
        }
        val ns = disable("NoiseSuppressor", NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)
        }
        val aec = disable("AEC", AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)
        }
        return Report(agc, ns, aec)
    }

    @Synchronized
    fun release() {
        held.forEach { runCatching { it.release() } }
        held.clear()
    }

    private fun disable(name: String, available: Boolean, create: () -> AudioEffect?): State {
        if (!available) return State.UNAVAILABLE
        return runCatching {
            val effect = create() ?: return State.FAILED
            held += effect
            effect.enabled = false
            if (effect.enabled) {
                Log.w(TAG, "$name refused to switch off - device is processing the input")
                State.STUCK_ON
            } else {
                State.DISABLED
            }
        }.getOrElse {
            Log.w(TAG, "$name could not be attached: ${it.message}")
            State.FAILED
        }
    }
}
