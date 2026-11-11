#pragma once

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

#define WIFI_SNIFFER_SSID_MAX_LEN 32

#define WIFI_SNIFFER_MAX_CHANNEL 14

typedef enum {
    WIFI_SNIFFER_ENC_OPEN = 0,
    WIFI_SNIFFER_ENC_WEP,
    WIFI_SNIFFER_ENC_WPA,
    WIFI_SNIFFER_ENC_WPA2,
    WIFI_SNIFFER_ENC_WPA3,
} wifi_sniffer_encryption_t;

typedef struct {
    bool present;
    bool configured;
    bool locked;
    uint16_t config_methods;
} wifi_sniffer_wps_info_t;

typedef struct {
    uint8_t bssid[6];
    char ssid[WIFI_SNIFFER_SSID_MAX_LEN + 1];
    uint8_t ssid_len;
    bool ssid_hidden;
    uint8_t channel;
    int8_t rssi;
    wifi_sniffer_encryption_t encryption;
    wifi_sniffer_wps_info_t wps;
    uint32_t times_seen;
    int64_t first_seen_us;
    int64_t last_seen_us;
} wifi_sniffer_network_t;

typedef void (*wifi_sniffer_network_cb_t)(const wifi_sniffer_network_t *network,
                                           bool is_new, bool ssid_just_revealed, void *ctx);

esp_err_t wifi_sniffer_init(void);

esp_err_t wifi_sniffer_start(uint8_t channel, wifi_sniffer_network_cb_t cb, void *ctx);

esp_err_t wifi_sniffer_stop(void);

uint32_t wifi_sniffer_get_unique_count(void);

void wifi_sniffer_reset_session_count(void);

bool wifi_sniffer_note_known(const uint8_t bssid[6]);

uint32_t wifi_sniffer_get_table_size(void);

size_t wifi_sniffer_trim_table(uint32_t max_age_ms);

void wifi_sniffer_reset_table(void);

typedef struct {
    uint32_t table_size;
    uint32_t table_unnamed;
    uint32_t unique_count;
    uint32_t beacons;
    uint32_t probe_responses;
    uint32_t ssid_null_len;
    uint32_t ssid_zero_filled;
    uint32_t names_revealed_probe;
    uint32_t names_revealed_beacon;
    uint32_t name_kept;
    uint32_t alloc_failures;
    uint32_t assoc_requests;
    uint32_t names_revealed_assoc;
    uint32_t hop_switches;
    uint32_t hop_failures;

    uint32_t rx_by_channel[WIFI_SNIFFER_MAX_CHANNEL + 1];
} wifi_sniffer_stats_t;

void wifi_sniffer_get_stats(wifi_sniffer_stats_t *out);

#ifdef __cplusplus
}
#endif
