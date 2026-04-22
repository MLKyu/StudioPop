// JNI bridge for whisper.cpp on Android.
//
// 노출 함수 (Kotlin: com.mingeek.studiopop.data.caption.WhisperCppEngine):
//   nativeInit(modelPath: String): Long          → whisper context handle
//   nativeTranscribe(handle: Long, pcm: FloatArray, sampleRate: Int, language: String): String
//                                                → 결과 JSON (segments 배열)
//   nativeRelease(handle: Long)
//
// PCM 입력 사양: float32, 16kHz, mono, range [-1.0, 1.0]

#include <jni.h>
#include <android/log.h>
#include <whisper.h>

#include <atomic>
#include <string>
#include <vector>
#include <sstream>

#define LOG_TAG "WhisperCppJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 진행률 atomic — Kotlin 측에서 nativeProgress() 로 폴링.
// 0..100 범위. 새 transcribe 시작 시 0 으로 리셋, 완료 시 100.
static std::atomic<int> g_progress{0};

static void on_whisper_progress(struct whisper_context * /*ctx*/,
                                struct whisper_state * /*state*/,
                                int progress,
                                void * /*user_data*/) {
    g_progress.store(progress);
}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_mingeek_studiopop_data_caption_WhisperCppEngine_nativeProgress(
        JNIEnv * /*env*/, jobject /*this*/) {
    return g_progress.load();
}

JNIEXPORT jlong JNICALL
Java_com_mingeek_studiopop_data_caption_WhisperCppEngine_nativeInit(
        JNIEnv *env, jobject /* this */, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("whisper_init_from_file 실패: %s", path);
        return 0L;
    }
    LOGI("whisper_init OK: %p", (void *) ctx);
    return reinterpret_cast<jlong>(ctx);
}

static std::string escape_json(const std::string &s) {
    std::string out;
    out.reserve(s.size());
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_mingeek_studiopop_data_caption_WhisperCppEngine_nativeTranscribe(
        JNIEnv *env, jobject /* this */,
        jlong handle, jfloatArray pcm, jint sampleRate, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        return env->NewStringUTF("{\"error\":\"null context\"}");
    }

    const jsize n = env->GetArrayLength(pcm);
    jfloat *pcmPtr = env->GetFloatArrayElements(pcm, nullptr);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;
    wparams.single_segment   = false;
    wparams.no_context       = true;
    wparams.suppress_blank   = true;
    wparams.token_timestamps = false;
    wparams.n_threads        = 4;

    // 진행률 콜백 등록. g_progress=1 로 "시작됨" 을 바로 찍어 사용자가 "멈췄다" 로
    // 착각하지 않게 함 (whisper.cpp 는 내부적으로 기본 5% 단위로 progress_callback 발화).
    g_progress.store(1);
    wparams.progress_callback           = on_whisper_progress;
    wparams.progress_callback_user_data = nullptr;

    const char *lang = nullptr;
    if (language != nullptr) {
        lang = env->GetStringUTFChars(language, nullptr);
        if (lang != nullptr && lang[0] != '\0') {
            wparams.language = lang;
            wparams.detect_language = false;
        } else {
            wparams.detect_language = true;
        }
    }

    LOGI("whisper_full 시작: samples=%d, sr=%d, threads=%d, lang=%s",
         (int) n, sampleRate, wparams.n_threads,
         wparams.language ? wparams.language : "(auto)");
    int rc = whisper_full(ctx, wparams, pcmPtr, n);
    LOGI("whisper_full 종료: rc=%d, segments=%d", rc, rc == 0 ? whisper_full_n_segments(ctx) : 0);
    if (lang != nullptr) env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(pcm, pcmPtr, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full 실패 rc=%d", rc);
        std::string err = "{\"error\":\"whisper_full rc=" + std::to_string(rc) + "\"}";
        return env->NewStringUTF(err.c_str());
    }
    g_progress.store(100);

    const int segCount = whisper_full_n_segments(ctx);
    std::ostringstream json;
    json << "{\"segments\":[";
    for (int i = 0; i < segCount; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i); // 단위: 1/100 초
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
        if (i > 0) json << ",";
        json << "{\"t0_ms\":" << (t0 * 10)
             << ",\"t1_ms\":" << (t1 * 10)
             << ",\"text\":\"" << escape_json(text ? text : "") << "\"}";
    }
    json << "]}";

    return env->NewStringUTF(json.str().c_str());
}

JNIEXPORT void JNICALL
Java_com_mingeek_studiopop_data_caption_WhisperCppEngine_nativeRelease(
        JNIEnv * /* env */, jobject /* this */, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
        LOGI("whisper_free OK: %p", (void *) ctx);
    }
}

} // extern "C"
