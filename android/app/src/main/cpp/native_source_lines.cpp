#include <jni.h>

#include "source_line_scan.h"

extern "C" JNIEXPORT jintArray JNICALL
Java_com_podcastapp_data_NativeSourceLines_scanNative(JNIEnv* env, jobject, jstring text) {
    if (text == nullptr) {
        return nullptr;
    }
    const jsize length = env->GetStringLength(text);
    if (length == 0) {
        return env->NewIntArray(0);
    }
    const jchar* chars = env->GetStringChars(text, nullptr);
    if (chars == nullptr) {
        return nullptr;  // Preserve any pending JVM exception.
    }
    jint fields[kScanBufferSize];
    const jsize size = ScanSourceLines(chars, length, fields) * kFieldsPerLine;
    env->ReleaseStringChars(text, chars);

    jintArray result = env->NewIntArray(size);
    if (result != nullptr && size != 0) {
        env->SetIntArrayRegion(result, 0, size, fields);
    }
    return result;
}
