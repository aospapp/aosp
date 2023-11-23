#include <gralloc3/gralloc_vendor_interface.h>

#include <android/hardware/graphics/mapper/2.0/IMapper.h>

#include "GrallocBufferDescriptor.h"
#include "mali_gralloc_buffer.h"
#include "mali_gralloc_bufferallocation.h"
#include "mali_gralloc_ion.h"
#include "mali_gralloc_formats.h"
#include "gralloc_buffer_priv.h"
#include "exynos_format.h"
#include "format_info.h"

namespace android::hardware::graphics::allocator::priv {

struct Descriptor {
    unsigned int size = 0;
    uint64_t producer_usage = 0;
    uint64_t consumer_usage = 0;

    struct PlaneDescriptor {
        int fd = -1;
        size_t size = 0;
        off_t offset = 0;
        int stride_byte = 0;
    };
    std::vector<PlaneDescriptor> planes;

    int width = 0;
    int height = 0;
    int stride_pixel = 0;
    int format = 0;
};

Descriptor *createDescriptor() { return new Descriptor(); }
void deleteDescriptor(Descriptor *descriptor) { delete descriptor; }

void setProducerUsage(Descriptor &descriptor, uint64_t usage) {
    descriptor.producer_usage = usage;
}

void setConsumerUsage(Descriptor &descriptor, uint64_t usage) {
    descriptor.consumer_usage = usage;
}

void setPlaneCount(Descriptor &descriptor, int count) {
    descriptor.planes.resize(count);
}

void setPlane(Descriptor &descriptor, int index, int fd, size_t size, off_t offset, int stride_byte) {
    descriptor.planes[index].fd = fd;
    descriptor.planes[index].size = size;
    descriptor.planes[index].offset = offset;
    descriptor.planes[index].stride_byte = stride_byte;
}

void setWidth(Descriptor &descriptor, int width) {
    descriptor.width = width;
}

void setHeight(Descriptor &descriptor, int height) {
    descriptor.height = height;
}

void setStridePixel(Descriptor &descriptor, int stride_pixel) {
    descriptor.stride_pixel = stride_pixel;
}

void setFormat(Descriptor &descriptor, int format) {
    descriptor.format = format;
}

uint8_t getFormatBitsPerPixel(uint64_t format) {
    auto idx = get_format_index(format);
    if (idx == -1) {
        ALOGE("getFormatBitsPerPixel failed, invalid format %" PRIu64 "", format);
        return 0;
    }
    return formats[idx].bpp[0];
}

buffer_handle_t createNativeHandle(const Descriptor &descriptor) {
    for (int i = 0; i < descriptor.planes.size(); ++i) {
        off64_t fd_size = lseek64(descriptor.planes[i].fd, 0, SEEK_END);
        if (fd_size < descriptor.planes[i].size) {
            ALOGE("createNativeHandle failed: plane[%d] requested size greater than fd size.",
                i);
            return nullptr;
        }
    }

    buffer_descriptor_t buffer_descriptor;

    buffer_descriptor.pixel_stride = descriptor.stride_pixel;
    buffer_descriptor.width = descriptor.width;
    buffer_descriptor.height = descriptor.height;
    buffer_descriptor.layer_count = 1;
    buffer_descriptor.hal_format = descriptor.format;
    buffer_descriptor.producer_usage = descriptor.producer_usage;
    buffer_descriptor.consumer_usage = descriptor.consumer_usage;
    buffer_descriptor.format_type = MALI_GRALLOC_FORMAT_TYPE_USAGE;
    buffer_descriptor.signature = sizeof(buffer_descriptor_t);

    buffer_descriptor.alloc_format = buffer_descriptor.internal_format
        = buffer_descriptor.hal_format;

    buffer_descriptor.fd_count = buffer_descriptor.plane_count
        = descriptor.planes.size();
    for (int i = 0; i < descriptor.planes.size(); ++i) {
        buffer_descriptor.sizes[i] = descriptor.planes[i].size;
    }

    auto format_index = get_format_index(descriptor.format);
    if (format_index == -1) {
        ALOGE("get_format_index failed, invalid format %d", descriptor.format);
        return 0;
    }
    for (int i = 0; i < descriptor.planes.size(); ++i) {
        uint8_t bpp = formats[format_index].bpp[i];
        if (bpp == 0) {
            ALOGE("createNativeHandle failed, format %d has bpp[%d]=0",
                descriptor.format, i);
            return nullptr;
        }
        buffer_descriptor.plane_info[i] = {
            .byte_stride = static_cast<uint32_t>((descriptor.planes[i].stride_byte * bpp) / 8),
            .alloc_width = buffer_descriptor.width,
            .alloc_height = buffer_descriptor.height,
        };
    }

    mali_gralloc_derive_format_and_size(nullptr, &buffer_descriptor);

    const gralloc_buffer_descriptor_t gralloc_buffer_descriptor =
        reinterpret_cast<const gralloc_buffer_descriptor_t>(&buffer_descriptor);

    buffer_handle_t tmp_buffer;
    bool shared;
    // TODO(modan@, make mali_gralloc_ion_allocate accept multiple fds)
    int allocResult = mali_gralloc_ion_allocate(&gralloc_buffer_descriptor, 1, &tmp_buffer, &shared,
        descriptor.planes[0].fd);
    if (allocResult < 0) {
        return nullptr;
    }

    private_handle_t *private_handle = const_cast<private_handle_t *>(
        static_cast<const private_handle_t *>(tmp_buffer));
    // TODO(modan@, handle all plane offsets)
    private_handle->offset = private_handle->plane_info[0].offset = descriptor.planes[0].offset;

    int err = gralloc_buffer_attr_allocate(private_handle);
    if (err) {
        ALOGE("createNativeHandle failed, gralloc_buffer_attr_allocate returned %d.", err);
        mali_gralloc_ion_free(private_handle);
        return nullptr;
    }
    return tmp_buffer;
}

}  // namespace android::hardware::graphics::allocator::priv
