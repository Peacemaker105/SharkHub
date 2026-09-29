cmake_minimum_required(VERSION 3.22.1)
project(sharkcam LANGUAGES CXX)

# Camera access on the DiLink 5 / SA8155P head unit goes through Qualcomm's userspace QCarCam
# client (/vendor/lib64/libais_client.so), dlopen'd at runtime — nothing here links against it.
# See docs/CAMERAS_SENTRY.md.

# JNI library: in-process probe (does the linker let an app dlopen the vendor lib?) and, later, the
# frame bridge.
add_library(sharkcam SHARED camprobe.cpp)
target_link_libraries(sharkcam log dl)

# Standalone sidecar, shipped as lib*.so so it lands in nativeLibraryDir (the one app-owned,
# executable location) and can be launched with ProcessBuilder. Same probe, from a plain executable:
# a separate process gets the default linker namespace rather than the app's restricted one.
add_executable(libsharkcam_sidecar.so sidecar.cpp)
target_link_libraries(libsharkcam_sidecar.so log dl)
