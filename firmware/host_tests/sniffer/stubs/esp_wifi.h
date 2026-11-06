#pragma once
#include <stdint.h>
#include "esp_err.h"
typedef enum { WIFI_PKT_MGMT = 0, WIFI_PKT_CTRL, WIFI_PKT_DATA } wifi_promiscuous_pkt_type_t;
typedef struct { int8_t rssi; uint8_t channel; uint16_t sig_len; } wifi_pkt_rx_ctrl_t;
typedef struct { wifi_pkt_rx_ctrl_t rx_ctrl; uint8_t payload[0]; } wifi_promiscuous_pkt_t;
typedef struct { uint32_t filter_mask; } wifi_promiscuous_filter_t;
#define WIFI_PROMIS_FILTER_MASK_MGMT 1u
typedef enum { WIFI_SECOND_CHAN_NONE = 0 } wifi_second_chan_t;
typedef struct { int dummy; } wifi_init_config_t;
#define WIFI_INIT_CONFIG_DEFAULT() { 0 }
typedef enum { WIFI_STORAGE_RAM = 0 } wifi_storage_t;
typedef enum { WIFI_MODE_NULL = 0 } wifi_mode_t;
typedef enum { WIFI_COUNTRY_POLICY_MANUAL = 1 } wifi_country_policy_t;
typedef struct { char cc[3]; uint8_t schan; uint8_t nchan; int8_t max_tx_power; wifi_country_policy_t policy; } wifi_country_t;
esp_err_t esp_wifi_init(const wifi_init_config_t *c);
esp_err_t esp_wifi_set_storage(wifi_storage_t s);
esp_err_t esp_wifi_set_mode(wifi_mode_t m);
esp_err_t esp_wifi_start(void);
esp_err_t esp_wifi_set_country(const wifi_country_t *c);
esp_err_t esp_wifi_set_promiscuous_filter(const wifi_promiscuous_filter_t *f);
esp_err_t esp_wifi_set_promiscuous_rx_cb(void (*cb)(void *, wifi_promiscuous_pkt_type_t));
esp_err_t esp_wifi_set_promiscuous(int en);
esp_err_t esp_wifi_set_channel(uint8_t ch, wifi_second_chan_t s);
