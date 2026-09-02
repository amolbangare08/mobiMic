package com.amol.mobimic.audio

/**
 * The DSP parameter set, as the UI sees it.
 *
 * Serialised into one packed float array and published as a unit, because the
 * audio thread has to see a consistent set of values - never half of an old
 * preset and half of a new one. The index order here is the contract with
 * nativeSetParams() in jni_bridge.cpp; change one and you must change the other.
 */
data class EqBandSettings(
    val enabled: Boolean = true,
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val label: String,
)

data class DspSettings(
    val enabled: Boolean = true,
    val inputGainDb: Float = 0f,
    val outputGainDb: Float = 0f,

    val hpfEnabled: Boolean = true,
    val hpfHz: Float = 80f,

    val gateEnabled: Boolean = true,
    val gateThresholdDb: Float = -45f,
    val gateRatio: Float = 4f,
    val gateAttackMs: Float = 1f,
    val gateHoldMs: Float = 50f,
    val gateReleaseMs: Float = 200f,
    val gateHysteresisDb: Float = 6f,

    val nsEnabled: Boolean = false,
    val nsMix: Float = 1f,

    val eqEnabled: Boolean = true,
    val eq: List<EqBandSettings> = defaultBands(),

    val deEsserEnabled: Boolean = true,
    val deEsserSplitHz: Float = 5000f,
    val deEsserThresholdDb: Float = -28f,
    val deEsserRatio: Float = 4f,

    val compressorEnabled: Boolean = true,
    val compThresholdDb: Float = -22f,
    val compRatio: Float = 3f,
    val compKneeDb: Float = 6f,
    val compAttackMs: Float = 10f,
    val compReleaseMs: Float = 120f,
    val compMakeupDb: Float = 0f,
    val compAutoMakeup: Boolean = true,

    val saturationEnabled: Boolean = false,
    val saturationDriveDb: Float = 3f,
    val saturationMix: Float = 0.5f,

    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -1f,
    val limiterReleaseMs: Float = 80f,
    val limiterLookaheadMs: Float = 2f,
) {

    fun toFloatArray(): FloatArray {
        val out = FloatArray(PARAM_COUNT)
        var i = 0
        fun put(value: Float) { out[i++] = value }
        fun put(flag: Boolean) { out[i++] = if (flag) 1f else 0f }

        put(enabled)
        put(inputGainDb)
        put(outputGainDb)

        put(hpfEnabled)
        put(hpfHz)

        put(gateEnabled)
        put(gateThresholdDb)
        put(gateRatio)
        put(gateAttackMs)
        put(gateHoldMs)
        put(gateReleaseMs)
        put(gateHysteresisDb)

        put(nsEnabled)
        put(nsMix)

        put(eqEnabled)
        for (band in eq) {
            put(band.enabled)
            put(band.frequencyHz)
            put(band.gainDb)
            put(band.q)
        }

        put(deEsserEnabled)
        put(deEsserSplitHz)
        put(deEsserThresholdDb)
        put(deEsserRatio)

        put(compressorEnabled)
        put(compThresholdDb)
        put(compRatio)
        put(compKneeDb)
        put(compAttackMs)
        put(compReleaseMs)
        put(compMakeupDb)
        put(compAutoMakeup)

        put(saturationEnabled)
        put(saturationDriveDb)
        put(saturationMix)

        put(limiterEnabled)
        put(limiterCeilingDb)
        put(limiterReleaseMs)
        put(limiterLookaheadMs)

        return out
    }

    fun withBand(index: Int, transform: (EqBandSettings) -> EqBandSettings): DspSettings =
        copy(eq = eq.mapIndexed { i, band -> if (i == index) transform(band) else band })

    companion object {
        /** Must match kExpected in nativeSetParams(). */
        const val PARAM_COUNT = 54

        /** Inverse of [toFloatArray], used when restoring a saved preset. */
        fun fromFloatArray(v: FloatArray): DspSettings? {
            if (v.size < PARAM_COUNT) return null
            var i = 0
            fun f(): Float = v[i++]
            fun b(): Boolean = v[i++] > 0.5f

            val enabled = b()
            val inputGainDb = f()
            val outputGainDb = f()
            val hpfEnabled = b()
            val hpfHz = f()
            val gateEnabled = b()
            val gateThresholdDb = f()
            val gateRatio = f()
            val gateAttackMs = f()
            val gateHoldMs = f()
            val gateReleaseMs = f()
            val gateHysteresisDb = f()
            val nsEnabled = b()
            val nsMix = f()
            val eqEnabled = b()
            val labels = defaultBands()
            val bands = (0 until labels.size).map { index ->
                EqBandSettings(
                    enabled = b(),
                    frequencyHz = f(),
                    gainDb = f(),
                    q = f(),
                    label = labels[index].label,
                )
            }
            return DspSettings(
                enabled = enabled,
                inputGainDb = inputGainDb,
                outputGainDb = outputGainDb,
                hpfEnabled = hpfEnabled,
                hpfHz = hpfHz,
                gateEnabled = gateEnabled,
                gateThresholdDb = gateThresholdDb,
                gateRatio = gateRatio,
                gateAttackMs = gateAttackMs,
                gateHoldMs = gateHoldMs,
                gateReleaseMs = gateReleaseMs,
                gateHysteresisDb = gateHysteresisDb,
                nsEnabled = nsEnabled,
                nsMix = nsMix,
                eqEnabled = eqEnabled,
                eq = bands,
                deEsserEnabled = b(),
                deEsserSplitHz = f(),
                deEsserThresholdDb = f(),
                deEsserRatio = f(),
                compressorEnabled = b(),
                compThresholdDb = f(),
                compRatio = f(),
                compKneeDb = f(),
                compAttackMs = f(),
                compReleaseMs = f(),
                compMakeupDb = f(),
                compAutoMakeup = b(),
                saturationEnabled = b(),
                saturationDriveDb = f(),
                saturationMix = f(),
                limiterEnabled = b(),
                limiterCeilingDb = f(),
                limiterReleaseMs = f(),
                limiterLookaheadMs = f(),
            )
        }

        /**
         * Band roles are fixed on the native side; only frequency, gain and Q move.
         * Band 0 is a low shelf, bands 1-3 are bells, band 4 is a high shelf.
         */
        fun defaultBands(): List<EqBandSettings> = listOf(
            EqBandSettings(frequencyHz = 120f, q = 0.7f, label = "Low shelf"),
            EqBandSettings(frequencyHz = 300f, q = 1.0f, label = "Low mid"),
            EqBandSettings(frequencyHz = 1200f, q = 1.0f, label = "Mid"),
            EqBandSettings(frequencyHz = 4000f, q = 1.0f, label = "Presence"),
            EqBandSettings(frequencyHz = 9000f, q = 0.7f, label = "Air"),
        )
    }
}

/**
 * Factory presets.
 *
 * Broadcast is the SM7B-adjacent target: rumble gone, a small presence lift, a
 * gentle 3:1 with a slow release so it densifies rather than pumps.
 */
object Presets {

    val flat = DspSettings(
        enabled = true,
        hpfEnabled = true,
        hpfHz = 60f,
        gateEnabled = false,
        eqEnabled = false,
        deEsserEnabled = false,
        compressorEnabled = false,
        limiterEnabled = true,
    )

    val broadcast = DspSettings(
        enabled = true,
        inputGainDb = 6f,
        hpfHz = 85f,
        gateEnabled = true,
        gateThresholdDb = -45f,
        nsEnabled = false,
        eq = DspSettings.defaultBands().let { bands ->
            listOf(
                bands[0].copy(gainDb = -2f, frequencyHz = 140f),
                bands[1].copy(gainDb = -3f, frequencyHz = 350f, q = 1.2f),
                bands[2].copy(gainDb = 1f, frequencyHz = 1500f),
                bands[3].copy(gainDb = 3.5f, frequencyHz = 4500f, q = 0.9f),
                bands[4].copy(gainDb = 2f, frequencyHz = 10000f),
            )
        },
        deEsserEnabled = true,
        deEsserThresholdDb = -30f,
        compThresholdDb = -24f,
        compRatio = 3f,
        compAttackMs = 12f,
        compReleaseMs = 140f,
        limiterCeilingDb = -1f,
    )

    val podcast = broadcast.copy(
        inputGainDb = 4f,
        compRatio = 2.5f,
        compThresholdDb = -20f,
        compReleaseMs = 200f,
        eq = DspSettings.defaultBands().let { bands ->
            listOf(
                bands[0].copy(gainDb = -1f),
                bands[1].copy(gainDb = -2f, frequencyHz = 400f),
                bands[2].copy(gainDb = 0.5f),
                bands[3].copy(gainDb = 2.5f),
                bands[4].copy(gainDb = 1.5f),
            )
        },
    )

    /** Noisy room: suppression on, gate tighter, less air to keep hiss down. */
    val meeting = broadcast.copy(
        nsEnabled = true,
        nsMix = 1f,
        gateThresholdDb = -38f,
        compRatio = 4f,
        eq = DspSettings.defaultBands().let { bands ->
            listOf(
                bands[0].copy(gainDb = -3f),
                bands[1].copy(gainDb = -2f),
                bands[2].copy(gainDb = 1.5f),
                bands[3].copy(gainDb = 3f),
                bands[4].copy(gainDb = 0f),
            )
        },
    )

    val all: List<Pair<String, DspSettings>> = listOf(
        "Broadcast" to broadcast,
        "Podcast" to podcast,
        "Meeting" to meeting,
        "Flat" to flat,
    )
}
