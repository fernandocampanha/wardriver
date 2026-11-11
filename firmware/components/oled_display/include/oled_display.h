#pragma once

#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

#define OLED_WIDTH  128
#define OLED_HEIGHT 64
#define OLED_PAGES  (OLED_HEIGHT / 8)

esp_err_t oled_display_init(void);

esp_err_t oled_display_start_guard(void);

typedef struct {
    uint32_t pushes_ok;
    uint32_t pushes_failed;
    uint32_t guard_pushes;
    uint32_t full_reinits;
    uint32_t bus_recoveries;
    uint32_t init_retries;
    uint32_t consecutive_failures;
    bool initialized;
    int64_t last_ok_age_ms;
} oled_stats_t;

void oled_display_get_stats(oled_stats_t *out);

void oled_display_clear(void);

void oled_display_draw_string(uint8_t x, uint8_t page, const char *str);

esp_err_t oled_display_update(void);

typedef enum {
    OLED_GPS_NONE = 0,
    OLED_GPS_PHONE,
    OLED_GPS_MODULE,
} oled_gps_source_t;

esp_err_t oled_display_show_status(uint32_t networks, uint8_t satellites,
                                    oled_gps_source_t gps_source, bool storage_ok, int storage_percent);

esp_err_t oled_display_show_message(const char *line1, const char *line2);

#ifdef __cplusplus
}
#endif
