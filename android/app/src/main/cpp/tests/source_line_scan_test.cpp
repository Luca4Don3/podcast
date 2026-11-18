#include "source_line_scan.h"

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

namespace {

void Expect(int32_t actual, int32_t expected, const char* context) {
    if (actual != expected) {
        fprintf(stderr, "%s: expected %d, got %d\n", context, expected, actual);
        exit(EXIT_FAILURE);
    }
}

void ExpectLine(const int32_t* record, int32_t line, int32_t start, int32_t end,
                int32_t pipe_count, int32_t first_pipe) {
    Expect(record[0], line, "original line number");
    Expect(record[1], start, "trimmed start");
    Expect(record[2], end, "exclusive trimmed end");
    Expect(record[3], pipe_count, "separator count");
    Expect(record[4], first_pipe, "absolute first separator");
}

template <size_t N>
int32_t ScanLiteral(const char16_t (&text)[N], int32_t (&out)[kScanBufferSize]) {
    uint16_t code_units[N];
    for (size_t i = 0; i < N - 1; ++i) {
        code_units[i] = text[i];
    }
    return ScanSourceLines(code_units, N - 1, out);
}

void EmptyAndMixedLines() {
    int32_t out[kScanBufferSize];
    Expect(ScanSourceLines(nullptr, 0, out), 0, "empty string");
    Expect(ScanLiteral(u" \r\n\u3000\n\r\t\u00a0", out), 0, "blank lines");
    Expect(ScanLiteral(u" \r\n\u3000\u64AD\U0001F3A7| https://x/%7C\u00A0\r\rfoo|| |\n|\r\nx\n\n", out),
           4, "mixed lines");
    ExpectLine(out, 2, 4, 22, 1, 7);
    ExpectLine(out + 5, 4, 25, 32, 3, 28);
    ExpectLine(out + 10, 5, 33, 34, 1, 33);
    ExpectLine(out + 15, 6, 36, 37, 0, -1);
    Expect(ScanLiteral(u"\n\rx|", out), 1, "LF CR as separate breaks");
    ExpectLine(out, 3, 2, 4, 1, 3);
    Expect(ScanLiteral(u"\u2028x\u2029y\u2028", out), 1, "Unicode separators are not line breaks");
    ExpectLine(out, 1, 1, 4, 0, -1);
}

void WhitespaceAndControls() {
    int32_t out[kScanBufferSize];
    const uint16_t whitespace[] = {
        0x0009, 0x000B, 0x000C, 0x001C, 0x001D, 0x001E, 0x001F, 0x0020,
        0x00A0, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005,
        0x2006, 0x2007, 0x2008, 0x2009, 0x200A, 0x2028, 0x2029, 0x202F,
        0x205F, 0x3000,
    };
    for (uint16_t c : whitespace) {
        const uint16_t text[] = {c, u'x', c};
        Expect(ScanSourceLines(text, 3, out), 1, "whitespace at edges");
        ExpectLine(out, 1, 1, 2, 0, -1);
    }
    // These remain in the span for Kotlin's URL/name rules to reject when appropriate.
    const uint16_t retained[] = {0x0000, 0x0001, 0x0008, 0x000E, 0x001B, 0x007F,
                                 0x0085, 0x009F, 0x180E, 0x200B, 0xFEFF, 0xD800, 0xDC00};
    for (uint16_t c : retained) {
        const uint16_t text[] = {u' ', c, u'|', c, u' '};
        Expect(ScanSourceLines(text, 5, out), 1, "retained code units");
        ExpectLine(out, 1, 1, 4, 1, 2);
    }
}

void BatchBound() {
    int32_t out[kScanBufferSize];
    uint16_t text[102 * 4];
    for (int32_t i = 0; i < 102; ++i) {
        text[i * 4] = u'x';
        text[i * 4 + 1] = u'\r';
        text[i * 4 + 2] = u'\n';
        text[i * 4 + 3] = u'\n';
    }
    out[100 * kFieldsPerLine] = -42;
    Expect(ScanSourceLines(text, 100 * 4, out), 100, "100 nonblank lines with interspersed blanks");
    Expect(out[100 * kFieldsPerLine], -42, "write only returned records");
    Expect(ScanSourceLines(text, 102 * 4, out), 101, "101st nonblank line is the final record");
    for (int32_t i = 0; i < 101; ++i) {
        ExpectLine(out + i * kFieldsPerLine, 2 * i + 1, 4 * i, 4 * i + 1, 0, -1);
    }
}

}  // namespace

int main() {
    EmptyAndMixedLines();
    WhitespaceAndControls();
    BatchBound();
}
