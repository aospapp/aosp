#include "IOStream.h"

#include "GL2Encoder.h"

#include <GLES3/gl31.h>

#include <vector>

void IOStream::readbackPixels(void* context, int width, int height, unsigned int format, unsigned int type, void* pixels) {
    GL2Encoder *ctx = (GL2Encoder *)context;
    assert (ctx->state() != NULL);

    int startOffset = 0;
    int pixelRowSize = 0;
    int totalRowSize = 0;
    int skipRows = 0;

    ctx->state()->getPackingOffsets2D(width, height, format, type,
                                      &startOffset,
                                      &pixelRowSize,
                                      &totalRowSize,
                                      &skipRows);

    size_t pixelDataSize =
        ctx->state()->pixelDataSize(
            width, height, 1, format, type, 1 /* is pack */);

    if (startOffset == 0 &&
        pixelRowSize == totalRowSize) {
        // fast path
        readback(pixels, pixelDataSize);
    } else if (pixelRowSize == totalRowSize) {
        // fast path but with skip in the beginning
        std::vector<char> paddingToDiscard(startOffset, 0);
        readback(&paddingToDiscard[0], startOffset);
        readback((char*)pixels + startOffset, pixelDataSize - startOffset);
    } else {
        int totalReadback = 0;

        if (startOffset > 0) {
            std::vector<char> paddingToDiscard(startOffset, 0);
            readback(&paddingToDiscard[0], startOffset);
            totalReadback += startOffset;
        }
        // need to read back row by row
        size_t paddingSize = totalRowSize - pixelRowSize;
        std::vector<char> paddingToDiscard(paddingSize, 0);

        char* start = (char*)pixels + startOffset;

        for (int i = 0; i < height; i++) {
            readback(start, pixelRowSize);
            totalReadback += pixelRowSize;
            readback(&paddingToDiscard[0], paddingSize);
            totalReadback += paddingSize;
            start += totalRowSize;
        }
    }
}
