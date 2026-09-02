package com.amol.mobimic.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Connection settings, persisted so the app comes back pointing at the same PC.
 *
 * SharedPreferences rather than DataStore: this is a handful of scalars read once
 * at startup, and the service needs them synchronously before the engine opens.
 */
class StreamSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mobimic", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var framesPerPacket: Int
        get() = prefs.getInt(KEY_FRAMES, DEFAULT_FRAMES_PER_PACKET)
        set(value) = prefs.edit().putInt(KEY_FRAMES, value).apply()

    var wireFormat: NativeAudioEngine.WireFormat
        get() = runCatching {
            NativeAudioEngine.WireFormat.valueOf(
                prefs.getString(KEY_FORMAT, null) ?: NativeAudioEngine.WireFormat.PCM_S16.name
            )
        }.getOrDefault(NativeAudioEngine.WireFormat.PCM_S16)
        set(value) = prefs.edit().putString(KEY_FORMAT, value.name).apply()

    /**
     * Allocate an audio session so AGC/NS/AEC can be forced off.
     *
     * Off by default: AAudio will not give a session id and the MMAP low-latency path
     * at the same time, and on devices that honour Unprocessed the override buys
     * nothing while costing tens of milliseconds. Turn it on for devices that process
     * the input anyway.
     */
    var forceEffectsOff: Boolean
        get() = prefs.getBoolean(KEY_FORCE_EFFECTS_OFF, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_EFFECTS_OFF, value).apply()

    /**
     * Which physical link to stream over.
     *
     * AUTO prefers a USB tether when one is up and a receiver answers on it, and
     * falls back to Wi-Fi. USB is worth preferring: it has almost no jitter, which
     * is what lets the receiver run a much smaller buffer.
     */
    enum class LinkMode { AUTO, USB, WIFI }

    var linkMode: LinkMode
        get() = runCatching {
            LinkMode.valueOf(prefs.getString(KEY_LINK_MODE, null) ?: LinkMode.AUTO.name)
        }.getOrDefault(LinkMode.AUTO)
        set(value) = prefs.edit().putString(KEY_LINK_MODE, value.name).apply()

    /** Look for the receiver by broadcast instead of using the stored address. */
    var autoDiscover: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DISCOVER, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DISCOVER, value).apply()

    /** Streaming starts automatically when capture starts. */
    var autoStream: Boolean
        get() = prefs.getBoolean(KEY_AUTO_STREAM, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_STREAM, value).apply()

    /**
     * The DSP block, stored as the same packed float array the native side takes.
     * One representation for transport and storage means one thing to keep in sync.
     */
    var dsp: DspSettings
        get() {
            val encoded = prefs.getString(KEY_DSP, null) ?: return Presets.broadcast
            val values = encoded.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray()
            return DspSettings.fromFloatArray(values) ?: Presets.broadcast
        }
        set(value) {
            prefs.edit()
                .putString(KEY_DSP, value.toFloatArray().joinToString(","))
                .apply()
        }

    var presetName: String
        get() = prefs.getString(KEY_PRESET, "Broadcast") ?: "Broadcast"
        set(value) = prefs.edit().putString(KEY_PRESET, value).apply()

    companion object {
        /**
         * 10.0.2.2 is the emulator's alias for the host machine, which makes it the
         * right default while developing. On a real phone this becomes the PC's LAN
         * address.
         */
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = 47001

        /** 240 frames at 48 kHz is 5 ms; 508 bytes on the wire as s16 mono, well under any MTU. */
        const val DEFAULT_FRAMES_PER_PACKET = 240

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_FRAMES = "frames_per_packet"
        private const val KEY_FORMAT = "wire_format"
        private const val KEY_AUTO_STREAM = "auto_stream"
        private const val KEY_FORCE_EFFECTS_OFF = "force_effects_off"
        private const val KEY_LINK_MODE = "link_mode"
        private const val KEY_AUTO_DISCOVER = "auto_discover"
        private const val KEY_DSP = "dsp"
        private const val KEY_PRESET = "preset"
    }
}
