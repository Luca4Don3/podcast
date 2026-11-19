#include <jni.h>
#include "PodcastNativeCache.h"

namespace {
class UtfChars final {
public:
    UtfChars(JNIEnv* env, jstring str)
        : env_(env), str_(str), chars_(str ? env->GetStringUTFChars(str, nullptr) : nullptr) {}
    ~UtfChars() { if (chars_) env_->ReleaseStringUTFChars(str_, chars_); }
    UtfChars(const UtfChars&) = delete;
    UtfChars& operator=(const UtfChars&) = delete;
    const char* get() const { return chars_; }
private:
    JNIEnv* env_;
    jstring str_;
    const char* chars_;
};
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_podcastapp_data_feed_NativeOfflineCache_nativeTargetCount(JNIEnv*, jobject,
                                                                  jint policy, jint available) {
    return podcast_cache_target_count(policy, available);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_podcastapp_data_feed_NativeOfflineCache_nativeFileSize(JNIEnv* env, jobject,
                                                               jstring dir, jstring name) {
    const UtfChars directory(env, dir);
    if (!directory.get()) return 0;
    const UtfChars file(env, name);
    return file.get() ? podcast_cache_file_size(directory.get(), file.get()) : 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_podcastapp_data_feed_NativeOfflineCache_nativeCommit(JNIEnv* env, jobject,
                                                             jstring dir, jstring part,
                                                             jstring name, jlong expected) {
    const UtfChars directory(env, dir);
    if (!directory.get()) return -1;
    const UtfChars temp(env, part);
    if (!temp.get()) return -1;
    const UtfChars file(env, name);
    return file.get() ? podcast_cache_commit(directory.get(), temp.get(), file.get(), expected) : -1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_podcastapp_data_feed_NativeOfflineCache_nativeHasMediaSignature(JNIEnv* env, jobject,
                                                                       jbyteArray bytes, jint length) {
    if (!bytes || length <= 0 || length > env->GetArrayLength(bytes)) return JNI_FALSE;
    constexpr jsize maxPrefixLength = 16;
    const jsize count = length < maxPrefixLength ? length : maxPrefixLength;
    jbyte prefix[maxPrefixLength];
    env->GetByteArrayRegion(bytes, 0, count, prefix);
    if (env->ExceptionCheck()) return JNI_FALSE;
    return podcast_cache_has_media_signature(reinterpret_cast<const uint8_t*>(prefix), count) != 0
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_podcastapp_data_feed_NativeOfflineCache_nativePrune(JNIEnv* env, jobject,
                                                            jstring dir, jstring keep) {
    const UtfChars directory(env, dir);
    if (!directory.get()) return -1;
    const UtfChars names(env, keep);
    return names.get() ? podcast_cache_prune(directory.get(), names.get()) : -1;
}
