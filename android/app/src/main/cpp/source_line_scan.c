#include "source_line_scan.h"

#include <stdbool.h>

// Kotlin/JVM Char.isWhitespace is Character.isWhitespace || Character.isSpaceChar.
// In particular this trims NBSP and U+3000, but not NUL, DEL or C1 controls.
static inline bool IsTrimWhitespace(uint16_t c) {
    if (c <= 0x0020) {
        return (c >= 0x0009 && c <= 0x000D) || c >= 0x001C;
    }
    return c == 0x00A0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) ||
           c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
}

int32_t ScanSourceLines(const uint16_t* text, int32_t length, int32_t out[kScanBufferSize]) {
    int32_t position = 0;
    int32_t line_number = 0;
    int32_t count = 0;
    while (position < length) {
        ++line_number;
        int32_t start = position;
        int32_t pipe_count = 0;
        int32_t first_pipe = -1;
        for (; position < length; ++position) {
            const uint16_t c = text[position];
            if (c == '\r' || c == '\n') {
                break;
            }
            if (c == '|') {
                if (pipe_count == 0) {
                    first_pipe = position;
                }
                ++pipe_count;
            }
        }

        int32_t end = position;
        // Pipes cannot be trimmed, so counting them in the raw line is equivalent.
        while (start < end && IsTrimWhitespace(text[start])) {
            ++start;
        }
        if (start < end) {
            while (IsTrimWhitespace(text[end - 1])) {
                --end;
            }
            int32_t* record = out + count * kFieldsPerLine;
            record[0] = line_number;
            record[1] = start;
            record[2] = end;
            record[3] = pipe_count;
            record[4] = first_pipe;
            if (++count == kMaxScannedLines) {
                break;
            }
        }

        // Kotlin lineSequence treats CRLF as one break and bare CR and LF as breaks.
        if (position < length) {
            const uint16_t newline = text[position++];
            if (newline == '\r' && position < length && text[position] == '\n') {
                ++position;
            }
        }
    }
    return count;
}
