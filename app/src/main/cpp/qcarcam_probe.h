// Shared by the JNI lib and the sidecar: try to reach Qualcomm's QCarCam client the way OverDrive
// does on DiLink 5 (dlopen + dlsym on /vendor/lib64/libais_client.so) and describe what happened.
// Read-only: nothing is initialised or opened unless `init` is true.
#pragma once
#include <dlfcn.h>
#include <string>

inline std::string qcarcamProbe(bool init) {
    std::string r;
    const char* path = "/vendor/lib64/libais_client.so";
    dlerror();
    void* h = dlopen(path, RTLD_NOW);
    if (!h) {
        const char* e = dlerror();
        r += "dlopen: FAILED ";
        r += e ? e : "(no dlerror)";
        return r;
    }
    r += "dlopen: ok";
    const char* syms[] = {
        "qcarcam_initialize", "qcarcam_open", "qcarcam_start", "qcarcam_stop", "qcarcam_close",
        "qcarcam_uninitialize", "qcarcam_query_inputs", "qcarcam_s_buffers", "qcarcam_get_frame",
        "qcarcam_release_frame", "qcarcam_s_param", "qcarcam_g_param",
    };
    for (const char* s : syms) {
        void* p = dlsym(h, s);
        r += "; ";
        r += s;
        r += p ? ": ok" : ": MISSING";
    }
    if (init) {
        typedef int (*init_fn)(void*);
        typedef int (*uninit_fn)();
        init_fn qi = (init_fn) dlsym(h, "qcarcam_initialize");
        uninit_fn qu = (uninit_fn) dlsym(h, "qcarcam_uninitialize");
        if (qi && qu) {
            int rc = qi(nullptr);
            r += "; initialize(null) -> " + std::to_string(rc);
            if (rc == 0) {
                int rc2 = qu();
                r += "; uninitialize -> " + std::to_string(rc2);
            }
        }
    }
    dlclose(h);
    return r;
}
