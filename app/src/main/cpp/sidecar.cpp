// Standalone probe process (launched by NativeCamProbe.sidecar()). Prints one line and exits.
// Usage: libsharkcam_sidecar.so [--init]
#include <cstdio>
#include <cstring>
#include "qcarcam_probe.h"

int main(int argc, char** argv) {
    bool init = argc > 1 && std::strcmp(argv[1], "--init") == 0;
    std::string r = qcarcamProbe(init);
    std::printf("%s\n", r.c_str());
    return 0;
}
