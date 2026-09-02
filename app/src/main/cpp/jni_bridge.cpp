#include <jni.h>

#include <string>

#include "AudioEngine.h"
#include "Common.h"
#include "dsp/Params.h"

using mobimic::AudioEngine;

namespace {

std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeOpen(JNIEnv*, jobject, jboolean allocateSession) {
    // Returns 0 on success or a negative oboe::Result. The session id is a separate
    // getter: SessionId::None is -1, which is a successful open, not a failure.
    const int32_t result = AudioEngine::instance().open(allocateSession == JNI_TRUE);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeStart(JNIEnv*, jobject) {
    return AudioEngine::instance().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeStop(JNIEnv*, jobject) {
    AudioEngine::instance().stop();
}

JNIEXPORT void JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeClose(JNIEnv*, jobject) {
    AudioEngine::instance().close();
}

JNIEXPORT jboolean JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeIsRunning(JNIEnv*, jobject) {
    return AudioEngine::instance().isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeSessionId(JNIEnv*, jobject) {
    return AudioEngine::instance().sessionId();
}

JNIEXPORT jint JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativePresetUsed(JNIEnv*, jobject) {
    return static_cast<jint>(AudioEngine::instance().presetUsed());
}

JNIEXPORT jboolean JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeStartRecording(JNIEnv* env, jobject,
                                                                   jstring path, jint source) {
    const auto tap = source == 1 ? AudioEngine::RecordSource::Processed
                                 : AudioEngine::RecordSource::Raw;
    return AudioEngine::instance().startRecording(toStdString(env, path), tap) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeStopRecording(JNIEnv*, jobject) {
    AudioEngine::instance().stopRecording();
}

JNIEXPORT jboolean JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeIsRecording(JNIEnv*, jobject) {
    return AudioEngine::instance().isRecording() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeRecordingPath(JNIEnv* env, jobject) {
    return env->NewStringUTF(AudioEngine::instance().recordingPath().c_str());
}

JNIEXPORT void JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeProbePaths(JNIEnv*, jobject) {
    AudioEngine::instance().probePaths();
}

JNIEXPORT void JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeResetStats(JNIEnv*, jobject) {
    AudioEngine::instance().resetStats();
}

JNIEXPORT jboolean JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeSetTarget(JNIEnv* env, jobject, jstring host, jint port) {
    return AudioEngine::instance().setTarget(toStdString(env, host),
                                             static_cast<uint16_t>(port)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeSetLocalAddress(JNIEnv* env, jobject,
                                                                   jstring address,
                                                                   jboolean overUsb) {
    AudioEngine::instance().setLocalAddress(toStdString(env, address));
    AudioEngine::instance().setOverUsb(overUsb == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeStartStreaming(JNIEnv*, jobject,
                                                                   jint framesPerPacket,
                                                                   jint wireFormat) {
    return AudioEngine::instance().startStreaming(framesPerPacket, wireFormat) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeStopStreaming(JNIEnv*, jobject) {
    AudioEngine::instance().stopStreaming();
}

JNIEXPORT jboolean JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeIsStreaming(JNIEnv*, jobject) {
    return AudioEngine::instance().isStreaming() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeTargetDescription(JNIEnv* env, jobject) {
    return env->NewStringUTF(AudioEngine::instance().targetDescription().c_str());
}

/**
 * Applies a full parameter block.
 *
 * One packed float array rather than fifty setters: the audio thread must see a
 * consistent set of values, and publishing them as a unit is what guarantees that.
 * The layout is mirrored by DspSettings.toFloatArray() in Kotlin.
 */
JNIEXPORT void JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeSetParams(JNIEnv* env, jobject, jfloatArray in) {
    constexpr jsize kExpected = 54;
    if (in == nullptr || env->GetArrayLength(in) < kExpected) return;

    jfloat v[kExpected];
    env->GetFloatArrayRegion(in, 0, kExpected, v);

    auto& publisher = AudioEngine::instance().params();
    mobimic::dsp::DspParams& p = publisher.editable();
    int i = 0;
    const auto flag = [&v, &i]() { return v[i++] > 0.5f; };
    const auto value = [&v, &i]() { return v[i++]; };

    p.enabled = flag();
    p.inputGainDb = value();
    p.outputGainDb = value();

    p.hpfEnabled = flag();
    p.hpfHz = value();

    p.gateEnabled = flag();
    p.gateThresholdDb = value();
    p.gateRatio = value();
    p.gateAttackMs = value();
    p.gateHoldMs = value();
    p.gateReleaseMs = value();
    p.gateHysteresisDb = value();

    p.nsEnabled = flag();
    p.nsMix = value();

    p.eqEnabled = flag();
    for (int band = 0; band < mobimic::dsp::kEqBands; ++band) {
        p.eq[band].enabled = flag();
        p.eq[band].frequencyHz = value();
        p.eq[band].gainDb = value();
        p.eq[band].q = value();
    }

    p.deEsserEnabled = flag();
    p.deEsserSplitHz = value();
    p.deEsserThresholdDb = value();
    p.deEsserRatio = value();

    p.compressorEnabled = flag();
    p.compThresholdDb = value();
    p.compRatio = value();
    p.compKneeDb = value();
    p.compAttackMs = value();
    p.compReleaseMs = value();
    p.compMakeupDb = value();
    p.compAutoMakeup = flag();

    p.saturationEnabled = flag();
    p.saturationDriveDb = value();
    p.saturationMix = value();

    p.limiterEnabled = flag();
    p.limiterCeilingDb = value();
    p.limiterReleaseMs = value();
    p.limiterLookaheadMs = value();

    publisher.publish();
}

/**
 * Fills a caller-allocated double[25] with the live stats. One JNI call per poll,
 * no allocation on either side. Index meanings are mirrored in NativeAudioEngine.Stats.
 */
JNIEXPORT void JNICALL
Java_com_amol_mobimic_audio_NativeAudioEngine_nativeGetStats(JNIEnv* env, jobject, jdoubleArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 25) return;
    auto& engine = AudioEngine::instance();

    jdouble values[25];
    values[0] = engine.peakLevel();
    values[1] = engine.rmsLevel();
    values[2] = engine.callbackLoad();
    values[3] = engine.xRunCount();
    values[4] = engine.callbackFrames();
    values[5] = engine.bufferSizeFrames();
    values[6] = engine.bufferCapacityFrames();
    values[7] = engine.framesPerBurst();
    values[8] = engine.sampleRate();
    values[9] = static_cast<jdouble>(engine.framesCaptured());
    values[10] = static_cast<jdouble>(engine.framesDropped());
    values[11] = static_cast<jdouble>(engine.recordedFrames());
    values[12] = static_cast<jdouble>(engine.packetsSent());
    values[13] = static_cast<jdouble>(engine.bytesSent());
    values[14] = static_cast<jdouble>(engine.sendErrors());
    values[15] = engine.chain().gateReductionDb();
    values[16] = engine.chain().compReductionDb();
    values[17] = engine.chain().deEsserReductionDb();
    values[18] = engine.chain().limiterReductionDb();
    values[19] = engine.chain().outputPeak();
    values[20] = engine.dspLatencyFrames();
    values[21] = engine.mmapUsed() ? 1.0 : 0.0;
    values[22] = engine.lowLatencyGranted() ? 1.0 : 0.0;
    values[23] = engine.exclusiveGranted() ? 1.0 : 0.0;
    values[24] = engine.measuredLatencyMs();

    env->SetDoubleArrayRegion(out, 0, 25, values);
}

} // extern "C"
