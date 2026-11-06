#pragma once

#include <stdbool.h>
#include "esp_err.h"
#include "gps_nmea.h"

#ifdef __cplusplus
extern "C" {
#endif









esp_err_t phone_gps_init(void);






void phone_gps_update(double lat, double lon, float accuracy_m);







bool phone_gps_get_data(gps_data_t *out);

#ifdef __cplusplus
}
#endif
