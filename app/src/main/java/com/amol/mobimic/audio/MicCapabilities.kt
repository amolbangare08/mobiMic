package com.amol.mobimic.audio

import android.content.Context
import android.media.AudioManager

/**
 * What the device claims about its own capture path, read before any stream opens.
 *
 * [unprocessedSupported] is the important one. When it is false the vendor applies
 * processing that cannot be fully defeated, and the user should be told that up
 * front rather than blaming the app for a coloured, gated capture.
 */
data class MicCapabilities(
    val unprocessedSupported: Boolean?,
    val nativeSampleRate: Int?,
    val nativeFramesPerBurst: Int?,
) {
    companion object {
        fun read(context: Context): MicCapabilities {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return MicCapabilities(
                unprocessedSupported = am
                    .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
                    ?.toBooleanStrictOrNull(),
                nativeSampleRate = am
                    .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                    ?.toIntOrNull(),
                nativeFramesPerBurst = am
                    .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                    ?.toIntOrNull(),
            )
        }
    }
}
