#pragma once


























#include <stdint.h>

#define WARDRIVER_SSID_BUF_LEN 33









#define WARDRIVER_LIVE_RECORD_MARKER 0x01


#define WARDRIVER_FLAG_SSID_HIDDEN   (1 << 0)
#define WARDRIVER_FLAG_HAS_FIX       (1 << 1)
#define WARDRIVER_FLAG_WPS_PRESENT   (1 << 2)
#define WARDRIVER_FLAG_WPS_CONFIGURED (1 << 3)
#define WARDRIVER_FLAG_WPS_LOCKED    (1 << 4)

#pragma pack(push, 1)
typedef struct {
    uint8_t  bssid[6];
    char     ssid[WARDRIVER_SSID_BUF_LEN];
    uint8_t  flags;
    uint8_t  channel;
    int8_t   rssi;
    uint8_t  encryption;
    uint32_t times_seen;
    uint16_t wps_config_methods;
    int32_t  lat_e7;
    int32_t  lon_e7;
    int16_t  alt_m;
    uint16_t hdop_x100;
    uint32_t utc_hhmmss;
    uint8_t  gps_quality;
    uint8_t  satellites;
    uint32_t uptime_s;
} wardriver_record_t;
#pragma pack(pop)

#define WARDRIVER_RECORD_SIZE ((size_t)sizeof(wardriver_record_t))
