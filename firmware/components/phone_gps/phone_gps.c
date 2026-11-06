#include "phone_gps.h"

#include <string.h>

#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "sdkconfig.h"

static gps_data_t s_data;
static SemaphoreHandle_t s_mutex = NULL;

esp_err_t phone_gps_init(void)
{
    s_mutex = xSemaphoreCreateMutex();
    if (s_mutex == NULL) return ESP_ERR_NO_MEM;

    memset(&s_data, 0, sizeof(s_data));
    return ESP_OK;
}

void phone_gps_update(double lat, double lon, float accuracy_m)
{
    if (s_mutex == NULL) return;

    xSemaphoreTake(s_mutex, portMAX_DELAY);
    s_data.has_fix = true;
    s_data.latitude = lat;
    s_data.longitude = lon;
    s_data.hdop = accuracy_m;
    s_data.last_update_us = esp_timer_get_time();
    xSemaphoreGive(s_mutex);
}

bool phone_gps_get_data(gps_data_t *out)
{
    if (s_mutex == NULL) return false;

    bool fresh = false;
    xSemaphoreTake(s_mutex, portMAX_DELAY);
    if (s_data.has_fix && s_data.last_update_us > 0) {
        int64_t age_us = esp_timer_get_time() - s_data.last_update_us;
        int64_t stale_us = (int64_t)CONFIG_WARDRIVER_PHONE_GPS_STALE_MS * 1000;
        if (age_us < stale_us) {
            *out = s_data;
            fresh = true;
        }
    }
    xSemaphoreGive(s_mutex);
    return fresh;
}
