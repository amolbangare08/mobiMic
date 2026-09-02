package com.amol.mobimic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amol.mobimic.audio.DspSettings
import com.amol.mobimic.audio.EngineController
import com.amol.mobimic.audio.MicCapabilities
import com.amol.mobimic.audio.NativeAudioEngine
import com.amol.mobimic.audio.StreamSettings
import com.amol.mobimic.net.NetworkLinks
import com.amol.mobimic.net.ReceiverProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state holder.
 *
 * Two rules keep the UI out of the audio path: parameter changes are published as
 * whole blocks through a non-blocking JNI call, and metering is polled at 30 Hz
 * rather than pushed from the audio thread.
 */
class MobiMicViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = StreamSettings(application)

    val capabilities: MicCapabilities = MicCapabilities.read(application)

    private val _dsp = MutableStateFlow(settings.dsp)
    val dsp: StateFlow<DspSettings> = _dsp.asStateFlow()

    private val _presetName = MutableStateFlow(settings.presetName)
    val presetName: StateFlow<String> = _presetName.asStateFlow()

    private val _stats = MutableStateFlow(NativeAudioEngine.Stats())
    val stats: StateFlow<NativeAudioEngine.Stats> = _stats.asStateFlow()

    private val _host = MutableStateFlow(settings.host)
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow(settings.port)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _wireFormat = MutableStateFlow(settings.wireFormat)
    val wireFormat: StateFlow<NativeAudioEngine.WireFormat> = _wireFormat.asStateFlow()

    private val _autoStream = MutableStateFlow(settings.autoStream)
    val autoStream: StateFlow<Boolean> = _autoStream.asStateFlow()

    private val _linkMode = MutableStateFlow(settings.linkMode)
    val linkMode: StateFlow<StreamSettings.LinkMode> = _linkMode.asStateFlow()

    private val _autoDiscover = MutableStateFlow(settings.autoDiscover)
    val autoDiscover: StateFlow<Boolean> = _autoDiscover.asStateFlow()

    private val _links = MutableStateFlow<List<NetworkLinks.Link>>(emptyList())
    val links: StateFlow<List<NetworkLinks.Link>> = _links.asStateFlow()

    private val _discovered = MutableStateFlow<List<ReceiverProbe.Found>>(emptyList())
    val discovered: StateFlow<List<ReceiverProbe.Found>> = _discovered.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    val engineStatus = EngineController.status

    init {
        // Push the stored settings down before anything opens, so the first callback
        // already runs the user's chain rather than defaults.
        NativeAudioEngine.setParams(_dsp.value)

        viewModelScope.launch {
            while (true) {
                _stats.value = NativeAudioEngine.stats()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun updateDsp(transform: (DspSettings) -> DspSettings) {
        val updated = transform(_dsp.value)
        _dsp.value = updated
        settings.dsp = updated
        NativeAudioEngine.setParams(updated)
    }

    fun applyPreset(name: String, preset: DspSettings) {
        _presetName.value = name
        settings.presetName = name
        updateDsp { preset }
    }

    fun setHost(value: String) {
        _host.value = value
        settings.host = value
    }

    fun setPort(value: Int) {
        _port.value = value
        settings.port = value
    }

    fun setWireFormat(value: NativeAudioEngine.WireFormat) {
        _wireFormat.value = value
        settings.wireFormat = value
    }

    fun setAutoStream(value: Boolean) {
        _autoStream.value = value
        settings.autoStream = value
    }

    fun setLinkMode(value: StreamSettings.LinkMode) {
        _linkMode.value = value
        settings.linkMode = value
    }

    fun setAutoDiscover(value: Boolean) {
        _autoDiscover.value = value
        settings.autoDiscover = value
    }

    /** Sockets and blocking receives, so never on the main thread. */
    fun scanForReceivers() {
        if (_scanning.value) return
        _scanning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            _links.value = NetworkLinks.list()
            _discovered.value = ReceiverProbe.discover()
            _scanning.value = false
        }
    }

    /** Adopts a discovered receiver as the stored target. */
    fun useDiscovered(found: ReceiverProbe.Found) {
        setHost(found.host)
        setPort(found.port)
    }

    private companion object {
        /** 30 Hz is smooth to the eye and costs one JNI call per tick. */
        const val POLL_INTERVAL_MS = 33L
    }
}
