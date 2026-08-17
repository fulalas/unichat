// Minimal JNI shim over TDLib's JSON interface (libtdjson.so).
// Byte arrays instead of jstring on both directions: JNI's GetStringUTFChars
// is modified UTF-8 (CESU-8 for emoji), which TDLib rejects as invalid UTF-8.
#include <jni.h>
#include <stdlib.h>
#include <string.h>

extern int td_create_client_id(void);
extern void td_send(int client_id, const char *request);
extern const char *td_receive(double timeout);

static char *to_cstr(JNIEnv *env, jbyteArray arr) {
    jsize len = (*env)->GetArrayLength(env, arr);
    char *buf = malloc((size_t)len + 1);
    if (!buf) return NULL;
    (*env)->GetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    buf[len] = '\0';
    return buf;
}

// Raises OutOfMemoryError on the Kotlin side. Without it a failed to_cstr looked
// exactly like a delivered request (send returns void), so the request was
// silently dropped and Tg.request only noticed 15 s later, as a timeout.
static void throw_oom(JNIEnv *env) {
    jclass cls = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
    if (cls) (*env)->ThrowNew(env, cls, "tdjni: out of memory");
}

static jbyteArray to_jbytes(JNIEnv *env, const char *s) {
    if (!s) return NULL;
    jsize len = (jsize)strlen(s);
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *)s);
    return arr;
}

JNIEXPORT jint JNICALL
Java_org_unichat_app_TdJson_createClientId(JNIEnv *env, jclass cls) {
    return td_create_client_id();
}

JNIEXPORT void JNICALL
Java_org_unichat_app_TdJson_send(JNIEnv *env, jclass cls, jint client_id, jbyteArray request) {
    char *req = to_cstr(env, request);
    if (!req) { throw_oom(env); return; }
    td_send(client_id, req);
    free(req);
}

JNIEXPORT jbyteArray JNICALL
Java_org_unichat_app_TdJson_receive(JNIEnv *env, jclass cls, jdouble timeout) {
    // the returned pointer is valid until the next td_receive call; UniChat
    // calls receive from a single dedicated thread, and the copy into a Java
    // array happens right here before the next call can occur
    return to_jbytes(env, td_receive(timeout));
}
