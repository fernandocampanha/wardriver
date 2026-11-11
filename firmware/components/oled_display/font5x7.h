#pragma once
#include <stdint.h>

typedef struct {
    char c;
    uint8_t cols[5];
} font5x7_glyph_t;

extern const font5x7_glyph_t font5x7_table[];
extern const int font5x7_table_len;
