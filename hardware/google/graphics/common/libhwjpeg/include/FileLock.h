#include "android-base/thread_annotations.h"

// Encapsulates advisory file lock for a given field descriptor
class CAPABILITY("mutex") FileLock {
public:
    FileLock(int fd);
    ~FileLock() = default;

    // Acquires advisory file lock. This will block.
    int lock() ACQUIRE();
    // Releases advisory file lock.
    int unlock() RELEASE();

private:
    int fd_;
};