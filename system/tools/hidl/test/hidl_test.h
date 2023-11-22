#ifndef HIDL_TEST_H_
#define HIDL_TEST_H_

#define EACH_SERVER(THING)                  \
    do {                                    \
        THING<IMemoryTest>("memory");       \
        THING<IChild>("child");             \
        THING<IParent>("parent");           \
        THING<IFetcher>("fetcher");         \
        THING<IBar>("foo");                 \
        THING<IHash>("default");            \
        THING<IGraph>("graph");             \
        THING<IPointer>("pointer");         \
        THING<IMultithread>("multithread"); \
    } while (false)

#endif  // HIDL_TEST_H_