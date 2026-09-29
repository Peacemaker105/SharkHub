// JNI entry points for the in-process QCarCam probe (see qcarcam_probe.h).
#include <jni.h>
#include "qcarcam_probe.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_chris_sharkhub_car_NativeCamProbe_nativeProbe(JNIEnv* env, jobject, jboolean init) {
    return env->NewStringUTF(qcarcamProbe(init == JNI_TRUE).c_str());
}
