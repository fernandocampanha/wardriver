#pragma once

#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    bool has_fix;
    double latitude;
    double longitude;
    float altitude_m;
    float speed_knots;
    float heading_deg;
    float hdop;
    uint8_t fix_quality;
    uint8_t satellites;
    char utc_time[11];
    int64_t last_update_us;







    uint8_t sats_in_view;
    uint8_t sats_tracked;
    int8_t best_snr_db;
} gps_data_t;



esp_err_t gps_nmea_init(void);



esp_err_t gps_nmea_start(void);


void gps_nmea_get_data(gps_data_t *out);

#ifdef __cplusplus
}
#endif
