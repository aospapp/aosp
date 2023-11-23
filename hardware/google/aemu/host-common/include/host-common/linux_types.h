#pragma once

#ifndef __QNX__
struct iovec {
    void *iov_base;
    size_t iov_len;
};
#endif
