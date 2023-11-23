INCLUDE_DIRS="-I. -I../libdrm/include/drm -Iinclude -I/usr/include/libdrm -I./.ci/android_headers -I./tests/test_include"

CLANG="clang++-11"
CLANG_TIDY="clang-tidy-11"

CXXARGS="-fPIC -Wall -Werror -DPLATFORM_SDK_VERSION=30 -D__ANDROID_API__=30 -Wsign-promo -Wimplicit-fallthrough"
CXXARGS+=" -D_LIBCPP_ENABLE_THREAD_SAFETY_ANNOTATIONS -Wno-gnu-include-next "
CXXARGS+=" -fvisibility-inlines-hidden -std=gnu++17 -DHWC2_USE_CPP11 -DHWC2_INCLUDE_STRINGIFICATION -fno-rtti"

BUILD_FILES=(
backend/BackendClient.cpp
backend/Backend.cpp
backend/BackendManager.cpp
backend/BackendRCarDu.cpp
bufferinfo/BufferInfoGetter.cpp
#bufferinfo/BufferInfoMapperMetadata.cpp
bufferinfo/legacy/BufferInfoImagination.cpp
bufferinfo/legacy/BufferInfoLibdrm.cpp
bufferinfo/legacy/BufferInfoMaliHisi.cpp
bufferinfo/legacy/BufferInfoMaliMediatek.cpp
bufferinfo/legacy/BufferInfoMaliMeson.cpp
bufferinfo/legacy/BufferInfoMinigbm.cpp
compositor/DrmDisplayComposition.cpp
compositor/DrmDisplayCompositor.cpp
compositor/Planner.cpp
drm/DrmConnector.cpp
drm/DrmCrtc.cpp
drm/DrmDevice.cpp
drm/DrmEncoder.cpp
drm/DrmEventListener.cpp
drm/DrmFbImporter.cpp
drm/DrmMode.cpp
drm/DrmPlane.cpp
drm/DrmProperty.cpp
DrmHwcTwo.cpp
drm/ResourceManager.cpp
drm/VSyncWorker.cpp
tests/worker_test.cpp
utils/autolock.cpp
#utils/hwcutils.cpp
utils/Worker.cpp
)
