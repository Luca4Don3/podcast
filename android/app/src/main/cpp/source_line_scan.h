#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// The extra record lets Kotlin reject an oversized batch before validating any rows.
enum {
    kMaxScannedLines = 101,
    kFieldsPerLine = 5,
    kScanBufferSize = kMaxScannedLines * kFieldsPerLine,
};

// Returns the number of records written to out (which has space for kScanBufferSize ints).
// Each record is [1-based line, start, endExclusive, pipeCount, firstPipeOrMinus1].
// All offsets are UTF-16 code units in text. length is non-negative, and text
// may be null only when length is zero. Blank lines are skipped, not renumbered.
int32_t ScanSourceLines(const uint16_t* text, int32_t length, int32_t out[kScanBufferSize]);

#ifdef __cplusplus
}  // extern "C"
#endif
