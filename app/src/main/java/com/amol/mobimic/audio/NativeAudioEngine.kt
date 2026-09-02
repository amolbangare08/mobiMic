package com.amol.mobimic.audio

/**
 * Kotlin facade over the native Oboe engine.
 *
 * The native side is a process-wide singleton, so this object can be driven from
 * the service and read from the UI without passing a handle around.
 *
 * Nothing here blocks. Every call either flips an atomic or copies a small stat
 * block, so it is safe to call from the main thread.
 */
object NativeAudioEngine {

    init {
        System.loadLibrary("mobimic")
    }

    enum class InputPresetUsed { NONE, UNPROCESSED, VOICE_RECOGNITION, GENERIC }

    /** Mirrors packet::SampleFormat in cpp/net/Packet.h. */
    enum class WireFormat(val wireValue: Int, val label: String) {
        PCM_S16(0, "16-bit PCM"),
        PCM_F32(1, "32-bit float"),
    }

    /**
     * Snapshot of the engine's live counters.
     *
     * [callbackLoad] is the worst observed audio-callback duration as a fraction of
     * its deadline. It is the headroom figure the Phase 3 DSP chain has to fit into.
     */
    data class Stats(
        val peak: Float = 0f,
        val rms: Float = 0f,
        val callbackLoad: Float = 0f,
        val xRuns: Int = 0,
        val callbackFrames: Int = 0,
        val bufferSizeFrames: Int = 0,
        val bufferCapacityFrames: Int = 0,
        val framesPerBurst: Int = 0,
        val sampleRate: Int = 0,
        val framesCaptured: Long = 0,
        val framesDropped: Long = 0,
        val recordedFrames: Long = 0,
        val packetsSent: Long = 0,
        val bytesSent: Long = 0,
        val sendErrors: Long = 0,
        val gateReductionDb: Float = 0f,
        val compReductionDb: Float = 0f,
        val deEsserReductionDb: Float = 0f,
        val limiterReductionDb: Float = 0f,
        val outputPeak: Float = 0f,
        val dspLatencyFrames: Int = 0,
        val mmapUsed: Boolean = false,
        val lowLatencyGranted: Boolean = false,
        val exclusiveGranted: Boolean = false,
        /** Reported by the stream's own timestamps. 0 when the device will not say. */
        val measuredLatencyMs: Float = 0f,
    ) {
        /**
         * Callback period. For an input stream this, not the buffer size, is what
         * sets how often audio arrives - the buffer is an overrun cushion.
         */
        val burstMs: Float
            get() = if (sampleRate > 0) framesPerBurst * 1000f / sampleRate else 0f

        val recordedSeconds: Float
            get() = if (sampleRate > 0) recordedFrames.toFloat() / sampleRate else 0f

        /**
         * Everything the phone contributes: the hardware latency the stream reports
         * (falling back to one burst when it will not say) plus the chain's own
         * algorithmic delay.
         */
        val totalLatencyMs: Float
            get() {
                if (sampleRate <= 0) return 0f
                val capture = if (measuredLatencyMs > 0f) measuredLatencyMs else burstMs
                return capture + dspLatencyFrames * 1000f / sampleRate
            }
    }

    private val statsScratch = DoubleArray(25)

    /**
     * Opens the input stream without starting it. Returns 0 on success, or a negative
     * oboe error code. Read [sessionId] afterwards - it is -1 when no session was
     * allocated, which is a success, not a failure.
     *
     * [allocateSession] buys the ability to force AGC/NS/AEC off, at the cost of the
     * MMAP low-latency path - AAudio will not give both.
     */
    fun open(allocateSession: Boolean): Int = nativeOpen(allocateSession)

    fun start(): Boolean = nativeStart()

    fun stop() = nativeStop()

    fun close() = nativeClose()

    val isRunning: Boolean get() = nativeIsRunning()

    val sessionId: Int get() = nativeSessionId()

    val presetUsed: InputPresetUsed
        get() = when (nativePresetUsed()) {
            1 -> InputPresetUsed.UNPROCESSED
            2 -> InputPresetUsed.VOICE_RECOGNITION
            3 -> InputPresetUsed.GENERIC
            else -> InputPresetUsed.NONE
        }

    /** Where the WAV recorder taps the signal. Raw is the capture-quality reference. */
    enum class RecordSource(val wireValue: Int) { RAW(0), PROCESSED(1) }

    fun startRecording(path: String, source: RecordSource): Boolean =
        nativeStartRecording(path, source.wireValue)

    fun stopRecording() = nativeStopRecording()

    val isRecording: Boolean get() = nativeIsRecording()

    fun recordingPath(): String = nativeRecordingPath()

    fun resetStats() = nativeResetStats()

    /** Logs a table of what each stream configuration actually yields. */
    fun probePaths() = nativeProbePaths()

    fun setTarget(host: String, port: Int): Boolean = nativeSetTarget(host, port)

    /**
     * Pins outgoing packets to one local interface. Empty string lets the system
     * route. Must be set before [startStreaming]; the socket is bound at start.
     */
    fun setLocalAddress(address: String, overUsb: Boolean) =
        nativeSetLocalAddress(address, overUsb)

    fun startStreaming(framesPerPacket: Int, format: WireFormat): Boolean =
        nativeStartStreaming(framesPerPacket, format.wireValue)

    fun stopStreaming() = nativeStopStreaming()

    val isStreaming: Boolean get() = nativeIsStreaming()

    fun targetDescription(): String = nativeTargetDescription()

    /** Publishes a whole parameter block. Non-blocking; safe from the main thread. */
    fun setParams(settings: DspSettings) = nativeSetParams(settings.toFloatArray())

    fun stats(): Stats = synchronized(statsScratch) {
        nativeGetStats(statsScratch)
        Stats(
            peak = statsScratch[0].toFloat(),
            rms = statsScratch[1].toFloat(),
            callbackLoad = statsScratch[2].toFloat(),
            xRuns = statsScratch[3].toInt(),
            callbackFrames = statsScratch[4].toInt(),
            bufferSizeFrames = statsScratch[5].toInt(),
            bufferCapacityFrames = statsScratch[6].toInt(),
            framesPerBurst = statsScratch[7].toInt(),
            sampleRate = statsScratch[8].toInt(),
            framesCaptured = statsScratch[9].toLong(),
            framesDropped = statsScratch[10].toLong(),
            recordedFrames = statsScratch[11].toLong(),
            packetsSent = statsScratch[12].toLong(),
            bytesSent = statsScratch[13].toLong(),
            sendErrors = statsScratch[14].toLong(),
            gateReductionDb = statsScratch[15].toFloat(),
            compReductionDb = statsScratch[16].toFloat(),
            deEsserReductionDb = statsScratch[17].toFloat(),
            limiterReductionDb = statsScratch[18].toFloat(),
            outputPeak = statsScratch[19].toFloat(),
            dspLatencyFrames = statsScratch[20].toInt(),
            mmapUsed = statsScratch[21] > 0.5,
            lowLatencyGranted = statsScratch[22] > 0.5,
            exclusiveGranted = statsScratch[23] > 0.5,
            measuredLatencyMs = statsScratch[24].toFloat(),
        )
    }

    private external fun nativeOpen(allocateSession: Boolean): Int
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeClose()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSessionId(): Int
    private external fun nativePresetUsed(): Int
    private external fun nativeStartRecording(path: String, source: Int): Boolean
    private external fun nativeStopRecording()
    private external fun nativeIsRecording(): Boolean
    private external fun nativeRecordingPath(): String
    private external fun nativeResetStats()
    private external fun nativeProbePaths()
    private external fun nativeGetStats(out: DoubleArray)
    private external fun nativeSetTarget(host: String, port: Int): Boolean
    private external fun nativeSetLocalAddress(address: String, overUsb: Boolean)
    private external fun nativeStartStreaming(framesPerPacket: Int, wireFormat: Int): Boolean
    private external fun nativeStopStreaming()
    private external fun nativeIsStreaming(): Boolean
    private external fun nativeTargetDescription(): String
    private external fun nativeSetParams(values: FloatArray)
}
