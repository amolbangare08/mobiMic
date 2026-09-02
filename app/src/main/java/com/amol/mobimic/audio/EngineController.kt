package com.amol.mobimic.audio

import android.content.Context
import android.util.Log
import com.amol.mobimic.net.NetworkLinks
import com.amol.mobimic.net.ReceiverProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the open -> disable effects -> start sequence and publishes the result.
 *
 * The order matters: the audio session id only exists once the stream is open, and
 * the effect overrides must be in place before the stream starts, so this cannot be
 * collapsed into a single native call.
 */
object EngineController {

    private const val TAG = "mobiMic"

    data class Status(
        val running: Boolean = false,
        val preset: NativeAudioEngine.InputPresetUsed = NativeAudioEngine.InputPresetUsed.NONE,
        val sessionId: Int = -1,
        val effects: AudioEffectsControl.Report = AudioEffectsControl.Report(),
        val recording: Boolean = false,
        val recordingPath: String? = null,
        val streaming: Boolean = false,
        val target: String = "",
        val link: String = "",
        val overUsb: Boolean = false,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Synchronized
    fun startEngine(context: Context): Boolean {
        if (_status.value.running) return true

        val settings = StreamSettings(context)
        val allocateSession = settings.forceEffectsOff

        val openResult = NativeAudioEngine.open(allocateSession)
        if (openResult != 0) {
            val message = "Could not open input stream (oboe error $openResult)"
            Log.e(TAG, message)
            _status.value = Status(error = message)
            return false
        }
        val sessionId = NativeAudioEngine.sessionId

        // Attaching to effects is only possible when a session was allocated, and
        // allocating one is exactly what costs us the MMAP path.
        val effects = if (allocateSession) {
            AudioEffectsControl.disableAll(sessionId)
        } else {
            AudioEffectsControl.skipped()
        }

        if (!NativeAudioEngine.start()) {
            NativeAudioEngine.close()
            AudioEffectsControl.release()
            val message = "Stream opened but would not start"
            Log.e(TAG, message)
            _status.value = Status(error = message)
            return false
        }

        NativeAudioEngine.resetStats()
        _status.value = Status(
            running = true,
            preset = NativeAudioEngine.presetUsed,
            sessionId = sessionId,
            effects = effects,
        )
        Log.i(TAG, "Engine started: preset=${NativeAudioEngine.presetUsed} effects=$effects")

        if (settings.autoStream) {
            startStreaming(context)
        }
        return true
    }

    @Synchronized
    fun stopEngine() {
        NativeAudioEngine.stopRecording()
        NativeAudioEngine.stopStreaming()
        NativeAudioEngine.stop()
        NativeAudioEngine.close()
        AudioEffectsControl.release()
        _status.value = Status()
    }

    /**
     * Resolves the target and starts the sender.
     *
     * Resolution can block on DNS, so this must not be called from the main thread
     * with a hostname. With a literal IP - the normal case - it returns immediately.
     */
    /**
     * Chooses a link and a target, then starts the sender.
     *
     * Order of preference: a receiver that answered over USB, then one that
     * answered over Wi-Fi, then the manually configured address. The point of
     * discovering first is that a USB tether hands the phone a new subnet every
     * time it is plugged in, so a stored address is wrong exactly when the cable
     * is the link you wanted.
     *
     * Blocks on discovery and possibly DNS, so never call this from the main thread.
     */
    @Synchronized
    fun startStreaming(context: Context): Boolean {
        if (!_status.value.running) return false
        val settings = StreamSettings(context)

        val choice = chooseTarget(settings)
        if (choice == null) {
            val message = when (settings.linkMode) {
                StreamSettings.LinkMode.USB ->
                    "No receiver found over USB. Is USB tethering on, and the receiver running?"
                else -> "No receiver found at ${settings.host}:${settings.port}"
            }
            Log.w(TAG, message)
            _status.value = _status.value.copy(error = message)
            return false
        }

        // Bind before starting: the socket takes its local address at start time.
        NativeAudioEngine.setLocalAddress(choice.localAddress, choice.overUsb)

        if (!NativeAudioEngine.setTarget(choice.host, choice.port)) {
            _status.value = _status.value.copy(
                error = "Could not resolve ${choice.host}:${choice.port}"
            )
            return false
        }
        if (!NativeAudioEngine.startStreaming(settings.framesPerPacket, settings.wireFormat)) {
            _status.value = _status.value.copy(error = "Sender would not start")
            return false
        }

        Log.i(TAG, "streaming to ${choice.host}:${choice.port} via ${choice.link} " +
            "(usb=${choice.overUsb}, local=${choice.localAddress.ifEmpty { "system route" }})")
        _status.value = _status.value.copy(
            streaming = true,
            target = NativeAudioEngine.targetDescription(),
            link = choice.link,
            overUsb = choice.overUsb,
            error = null,
        )
        return true
    }

    private data class TargetChoice(
        val host: String,
        val port: Int,
        val link: String,
        val overUsb: Boolean,
        val localAddress: String,
    )

    private fun chooseTarget(settings: StreamSettings): TargetChoice? {
        val wantUsbOnly = settings.linkMode == StreamSettings.LinkMode.USB
        val wantWifiOnly = settings.linkMode == StreamSettings.LinkMode.WIFI

        if (settings.autoDiscover) {
            val links = NetworkLinks.list()
            val candidates = ReceiverProbe.discover()
                .filter { if (wantUsbOnly) it.overUsb else if (wantWifiOnly) !it.overUsb else true }
                // USB first, which is how ReceiverProbe already orders them.
                .sortedBy { if (it.overUsb) 0 else 1 }

            val best = candidates.firstOrNull()
            if (best != null) {
                val local = links.firstOrNull { it.interfaceName == best.viaInterface }
                return TargetChoice(
                    host = best.host,
                    port = best.port,
                    link = best.viaInterface,
                    overUsb = best.overUsb,
                    localAddress = local?.hostAddress.orEmpty(),
                )
            }
        }

        // USB-only was asked for and nothing answered: do not quietly use Wi-Fi.
        if (wantUsbOnly) return null

        // Fall back to whatever address the user configured.
        return TargetChoice(
            host = settings.host,
            port = settings.port,
            link = "configured",
            overUsb = false,
            localAddress = "",
        )
    }

    @Synchronized
    fun stopStreaming() {
        NativeAudioEngine.stopStreaming()
        _status.value = _status.value.copy(streaming = false)
    }

    /**
     * Starts a verification capture into the app's external files dir.
     *
     * Raw by default: the point of this recording is to show what the capsule
     * delivered, which a recording taken after the chain cannot do.
     */
    @Synchronized
    fun startRecording(
        directory: File,
        source: NativeAudioEngine.RecordSource = NativeAudioEngine.RecordSource.RAW,
    ): Boolean {
        if (!_status.value.running) return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val preset = NativeAudioEngine.presetUsed.name.lowercase()
        val tag = source.name.lowercase()
        val file = File(directory, "mobimic-$tag-$preset-$stamp.wav")
        if (!NativeAudioEngine.startRecording(file.absolutePath, source)) return false
        _status.value = _status.value.copy(recording = true, recordingPath = file.absolutePath)
        return true
    }

    @Synchronized
    fun stopRecording() {
        NativeAudioEngine.stopRecording()
        _status.value = _status.value.copy(recording = false)
    }
}
