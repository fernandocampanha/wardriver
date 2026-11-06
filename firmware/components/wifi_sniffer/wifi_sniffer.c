




























#include "wifi_sniffer.h"

#include <string.h>
#include <stdlib.h>

#include "esp_wifi.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"

static const char *TAG = "wifi_sniffer";









#define WIFI_SNIFFER_INITIAL_CAPACITY 64
#define WIFI_SNIFFER_QUEUE_LEN      16
#define WIFI_SNIFFER_MAX_FRAME_LEN  512

static const uint8_t OUI_MICROSOFT[3] = {0x00, 0x50, 0xF2};
static const uint8_t OUI_RSN[3]       = {0x00, 0x0F, 0xAC};


#define WPS_ATTR_STATE            0x1044
#define WPS_ATTR_AP_SETUP_LOCKED  0x1057
#define WPS_ATTR_CONFIG_METHODS   0x1008
#define WPS_ATTR_DEVICE_NAME      0x1011
#define WPS_ATTR_MANUFACTURER     0x1021
#define WPS_ATTR_MODEL_NAME       0x1023
#define WPS_ATTR_MODEL_NUMBER     0x1024


#define SNIFFER_FRAME_BEACON_LIKE 0
#define SNIFFER_FRAME_ASSOC       1
#define SNIFFER_FRAME_REASSOC     2

typedef struct {
    uint16_t len;
    int8_t rssi;
    uint8_t channel;
    uint8_t kind;
    uint8_t payload[WIFI_SNIFFER_MAX_FRAME_LEN];
} sniffer_raw_frame_t;


#ifndef CONFIG_WARDRIVER_HOP_FIRST_CHANNEL
#define CONFIG_WARDRIVER_HOP_FIRST_CHANNEL 1
#endif
#ifndef CONFIG_WARDRIVER_HOP_LAST_CHANNEL
#define CONFIG_WARDRIVER_HOP_LAST_CHANNEL 13
#endif
#ifndef CONFIG_WARDRIVER_HOP_DWELL_MS
#define CONFIG_WARDRIVER_HOP_DWELL_MS 110
#endif
#ifndef CONFIG_WARDRIVER_HOP_DWELL_MAIN_MS
#define CONFIG_WARDRIVER_HOP_DWELL_MAIN_MS 200
#endif

static wifi_sniffer_network_t *s_networks = NULL;
static int s_network_count = 0;
static int s_network_capacity = 0;
static SemaphoreHandle_t s_table_mutex = NULL;











#define SEEN_BLOOM_BITS  32768u
#define SEEN_BLOOM_BYTES (SEEN_BLOOM_BITS / 8u)
static uint8_t *s_seen_bloom = NULL;
static uint32_t s_seen_count = 0;



static wifi_sniffer_stats_t s_stats;



static volatile uint32_t s_rx_by_channel[WIFI_SNIFFER_MAX_CHANNEL + 1];




#define NAME_HINT_SLOTS   16
#define NAME_HINT_TTL_US  (30LL * 1000000)

typedef struct {
    uint8_t bssid[6];
    uint8_t ssid_len;
    char ssid[WIFI_SNIFFER_SSID_MAX_LEN + 1];
    int64_t at_us;
} name_hint_t;

static name_hint_t s_hints[NAME_HINT_SLOTS];
static int s_hint_next = 0;

static QueueHandle_t s_frame_queue = NULL;
static wifi_sniffer_network_cb_t s_user_cb = NULL;
static void *s_user_ctx = NULL;





static void parse_wps_ie(const uint8_t *data, uint16_t len, wifi_sniffer_wps_info_t *wps)
{
    wps->present = true;
    uint16_t offset = 0;

    while (offset + 4 <= len) {
        uint16_t attr_id  = (data[offset] << 8) | data[offset + 1];
        uint16_t attr_len = (data[offset + 2] << 8) | data[offset + 3];
        offset += 4;
        if (offset + attr_len > len) {
            break;
        }
        const uint8_t *val = &data[offset];

        switch (attr_id) {
            case WPS_ATTR_STATE:
                if (attr_len >= 1) wps->configured = (val[0] == 0x02);
                break;
            case WPS_ATTR_AP_SETUP_LOCKED:
                if (attr_len >= 1) wps->locked = (val[0] != 0x00);
                break;
            case WPS_ATTR_CONFIG_METHODS:
                if (attr_len >= 2) wps->config_methods = (val[0] << 8) | val[1];
                break;




            default:
                break;
        }
        offset += attr_len;
    }
}





static wifi_sniffer_encryption_t classify_rsn(const uint8_t *ie, uint8_t len)
{


    if (len < 8) return WIFI_SNIFFER_ENC_WPA2;

    uint16_t offset = 2 + 4;
    if (offset + 2 > len) return WIFI_SNIFFER_ENC_WPA2;

    uint16_t pairwise_count = ie[offset] | (ie[offset + 1] << 8);
    offset += 2 + (pairwise_count * 4);
    if (offset + 2 > len) return WIFI_SNIFFER_ENC_WPA2;

    uint16_t akm_count = ie[offset] | (ie[offset + 1] << 8);
    offset += 2;

    for (int i = 0; i < akm_count && offset + 4 <= len; i++, offset += 4) {
        if (memcmp(&ie[offset], OUI_RSN, 3) == 0) {
            uint8_t akm_type = ie[offset + 3];
            if (akm_type == 8 || akm_type == 24) {
                return WIFI_SNIFFER_ENC_WPA3;
            }
        }
    }
    return WIFI_SNIFFER_ENC_WPA2;
}










static bool seen_mark_locked(const uint8_t *bssid)
{
    if (s_seen_bloom == NULL) return false;

    uint32_t h1 = 2166136261u, h2 = 0x9E3779B1u;
    for (int i = 0; i < 6; i++) {
        h1 = (h1 ^ bssid[i]) * 16777619u;
        h2 = (h2 ^ bssid[i]) * 2246822519u;
    }
    h2 |= 1u;

    bool was_new = false;
    for (uint32_t k = 0; k < 3; k++) {
        uint32_t bit = (h1 + k * h2) & (SEEN_BLOOM_BITS - 1u);
        uint8_t mask = (uint8_t)(1u << (bit & 7u));
        if ((s_seen_bloom[bit >> 3] & mask) == 0) {
            s_seen_bloom[bit >> 3] |= mask;
            was_new = true;
        }
    }
    if (was_new) s_seen_count++;
    return was_new;
}






static bool ssid_ie_is_blank(const uint8_t *val, uint8_t len)
{
    for (uint8_t i = 0; i < len; i++) {
        if (val[i] != 0x00) return false;
    }
    return true;
}


static void hint_store_locked(const uint8_t *bssid, const char *ssid, uint8_t ssid_len, int64_t now)
{
    int idx = -1;
    for (int i = 0; i < NAME_HINT_SLOTS; i++) {
        if (s_hints[i].at_us > 0 && memcmp(s_hints[i].bssid, bssid, 6) == 0) { idx = i; break; }
    }
    if (idx < 0) {
        idx = s_hint_next;
        s_hint_next = (s_hint_next + 1) % NAME_HINT_SLOTS;
    }
    memcpy(s_hints[idx].bssid, bssid, 6);
    memcpy(s_hints[idx].ssid, ssid, ssid_len);
    s_hints[idx].ssid[ssid_len] = '\0';
    s_hints[idx].ssid_len = ssid_len;
    s_hints[idx].at_us = now;
}



static bool hint_take_locked(const uint8_t *bssid, int64_t now, char *ssid_out, uint8_t *len_out)
{
    for (int i = 0; i < NAME_HINT_SLOTS; i++) {
        if (s_hints[i].at_us > 0 && memcmp(s_hints[i].bssid, bssid, 6) == 0) {
            bool fresh = (now - s_hints[i].at_us) <= NAME_HINT_TTL_US;
            if (fresh) {
                memcpy(ssid_out, s_hints[i].ssid, s_hints[i].ssid_len + 1);
                *len_out = s_hints[i].ssid_len;
            }
            s_hints[i].at_us = 0;
            return fresh;
        }
    }
    return false;
}

static void upsert_network(wifi_sniffer_network_t *incoming, bool from_probe_resp)
{
    xSemaphoreTake(s_table_mutex, portMAX_DELAY);

    wifi_sniffer_network_t *slot = NULL;
    for (int i = 0; i < s_network_count; i++) {
        if (memcmp(s_networks[i].bssid, incoming->bssid, 6) == 0) {
            slot = &s_networks[i];
            break;
        }
    }

    bool is_new = false;
    bool ssid_just_revealed = false;
    int64_t now = esp_timer_get_time();

    if (slot == NULL) {
        if (s_network_count >= s_network_capacity) {
            int new_capacity = s_network_capacity * 2;
            wifi_sniffer_network_t *grown = realloc(s_networks, new_capacity * sizeof(wifi_sniffer_network_t));
            if (grown == NULL) {



                s_stats.alloc_failures++;
                seen_mark_locked(incoming->bssid);
                ESP_LOGE(TAG, "sem memoria para crescer tabela de redes (%d entradas, heap livre %u)",
                         s_network_capacity, (unsigned)esp_get_free_heap_size());
                xSemaphoreGive(s_table_mutex);
                return;
            }
            s_networks = grown;
            s_network_capacity = new_capacity;
            ESP_LOGI(TAG, "tabela de redes cresceu para %d entradas (heap livre %u)",
                     s_network_capacity, (unsigned)esp_get_free_heap_size());
        }
        slot = &s_networks[s_network_count++];
        *slot = *incoming;
        slot->times_seen = 1;
        slot->first_seen_us = now;
        slot->last_seen_us = now;
        is_new = true;
        if (incoming->ssid_len == 0 || incoming->ssid_hidden) {

            char hint_ssid[WIFI_SNIFFER_SSID_MAX_LEN + 1];
            uint8_t hint_len = 0;
            if (hint_take_locked(slot->bssid, now, hint_ssid, &hint_len)) {
                memcpy(slot->ssid, hint_ssid, hint_len + 1);
                slot->ssid_len = hint_len;
                slot->ssid_hidden = false;
                s_stats.names_revealed_assoc++;
            }
        }
    } else {
        bool was_unnamed = (slot->ssid_len == 0) || slot->ssid_hidden;

        slot->rssi = incoming->rssi;
        slot->channel = incoming->channel;
        slot->encryption = incoming->encryption;






        bool incoming_named = incoming->ssid_len > 0 && !incoming->ssid_hidden;
        if (incoming_named) {
            slot->ssid_len = incoming->ssid_len;
            memcpy(slot->ssid, incoming->ssid, incoming->ssid_len + 1);
            slot->ssid_hidden = false;
        } else if (!was_unnamed) {
            s_stats.name_kept++;
        }
        if (incoming->wps.present) {
            slot->wps = incoming->wps;
        }
        slot->times_seen++;
        slot->last_seen_us = now;





        ssid_just_revealed = was_unnamed && incoming_named;
        if (ssid_just_revealed) {
            if (from_probe_resp) s_stats.names_revealed_probe++;
            else                 s_stats.names_revealed_beacon++;
        }
    }




    if (!seen_mark_locked(incoming->bssid) && s_seen_bloom == NULL && is_new) {
        s_seen_count++;
    }

    wifi_sniffer_network_t snapshot = *slot;
    wifi_sniffer_network_cb_t cb = s_user_cb;
    void *ctx = s_user_ctx;
    xSemaphoreGive(s_table_mutex);

    if (cb != NULL) {
        cb(&snapshot, is_new, ssid_just_revealed, ctx);
    }
}








static void parse_beacon_frame(const uint8_t *payload, uint16_t len, int8_t rssi, uint8_t channel)
{

    if (len < 36) return;

    bool is_probe_resp = ((payload[0] & 0xFC) == 0x50);
    if (is_probe_resp) s_stats.probe_responses++; else s_stats.beacons++;

    wifi_sniffer_network_t net = {0};
    memcpy(net.bssid, &payload[10], 6);
    net.rssi = rssi;
    net.channel = channel;




    net.ssid_hidden = true;

    uint16_t capability = payload[34] | (payload[35] << 8);
    bool privacy = capability & 0x0010;
    net.encryption = privacy ? WIFI_SNIFFER_ENC_WEP : WIFI_SNIFFER_ENC_OPEN;

    uint16_t offset = 36;
    while (offset + 2 <= len) {
        uint8_t tag = payload[offset];
        uint8_t tag_len = payload[offset + 1];
        offset += 2;
        if (offset + tag_len > len) break;
        const uint8_t *val = &payload[offset];

        switch (tag) {
            case 0: {
                uint8_t ssid_len = tag_len > WIFI_SNIFFER_SSID_MAX_LEN ? WIFI_SNIFFER_SSID_MAX_LEN : tag_len;
                if (tag_len == 0) {
                    s_stats.ssid_null_len++;
                } else if (ssid_ie_is_blank(val, ssid_len)) {
                    s_stats.ssid_zero_filled++;
                }
                if (ssid_len == 0 || ssid_ie_is_blank(val, ssid_len)) {
                    net.ssid_len = 0;
                    net.ssid[0] = '\0';
                    net.ssid_hidden = true;
                } else {
                    net.ssid_len = ssid_len;
                    memcpy(net.ssid, val, ssid_len);
                    net.ssid[ssid_len] = '\0';
                    net.ssid_hidden = false;
                }
                break;
            }

            case 3:
                if (tag_len >= 1) net.channel = val[0];
                break;

            case 48:
                net.encryption = classify_rsn(val, tag_len);
                break;

            case 221:
                if (tag_len >= 4 && memcmp(val, OUI_MICROSOFT, 3) == 0) {
                    uint8_t vendor_type = val[3];
                    if (vendor_type == 1 &&
                        net.encryption != WIFI_SNIFFER_ENC_WPA2 &&
                        net.encryption != WIFI_SNIFFER_ENC_WPA3) {
                        net.encryption = WIFI_SNIFFER_ENC_WPA;
                    } else if (vendor_type == 4) {
                        parse_wps_ie(val + 4, tag_len - 4, &net.wps);
                    }
                }
                break;

            default:
                break;
        }
        offset += tag_len;
    }

    upsert_network(&net, is_probe_resp);
}






static bool ssid_from_ies(const uint8_t *ies, uint16_t len, char *out, uint8_t *out_len)
{
    uint16_t offset = 0;
    while (offset + 2 <= len) {
        uint8_t tag = ies[offset];
        uint8_t tag_len = ies[offset + 1];
        offset += 2;
        if (offset + tag_len > len) return false;
        if (tag == 0) {
            if (tag_len == 0 || tag_len > WIFI_SNIFFER_SSID_MAX_LEN || ssid_ie_is_blank(&ies[offset], tag_len)) return false;
            memcpy(out, &ies[offset], tag_len);
            out[tag_len] = '\0';
            *out_len = tag_len;
            return true;
        }
        offset += tag_len;
    }
    return false;
}






static void apply_name_hint(const uint8_t *bssid, const char *ssid, uint8_t ssid_len)
{
    wifi_sniffer_network_t snapshot;
    wifi_sniffer_network_cb_t cb = NULL;
    void *ctx = NULL;
    bool revealed = false;
    int64_t now = esp_timer_get_time();

    xSemaphoreTake(s_table_mutex, portMAX_DELAY);
    wifi_sniffer_network_t *slot = NULL;
    for (int i = 0; i < s_network_count; i++) {
        if (memcmp(s_networks[i].bssid, bssid, 6) == 0) { slot = &s_networks[i]; break; }
    }
    if (slot != NULL) {
        if (slot->ssid_len == 0 || slot->ssid_hidden) {
            memcpy(slot->ssid, ssid, ssid_len + 1);
            slot->ssid_len = ssid_len;
            slot->ssid_hidden = false;
            s_stats.names_revealed_assoc++;
            snapshot = *slot;
            cb = s_user_cb;
            ctx = s_user_ctx;
            revealed = true;
        }
    } else {
        hint_store_locked(bssid, ssid, ssid_len, now);
    }
    xSemaphoreGive(s_table_mutex);

    if (revealed && cb != NULL) {
        cb(&snapshot, false, true, ctx);
    }
}





static void handle_assoc_request(const sniffer_raw_frame_t *f)
{
    const uint16_t ie_off = (f->kind == SNIFFER_FRAME_REASSOC) ? 34 : 28;
    if (f->len < ie_off + 2) return;

    const uint8_t *addr1 = &f->payload[4];
    const uint8_t *bssid = &f->payload[16];
    if (memcmp(addr1, bssid, 6) != 0 || (bssid[0] & 0x01)) return;

    s_stats.assoc_requests++;

    char ssid[WIFI_SNIFFER_SSID_MAX_LEN + 1];
    uint8_t ssid_len = 0;
    if (!ssid_from_ies(&f->payload[ie_off], (uint16_t)(f->len - ie_off), ssid, &ssid_len)) return;
    apply_name_hint(bssid, ssid, ssid_len);
}


static void sniffer_process_frame(const sniffer_raw_frame_t *f)
{
    if (f->kind == SNIFFER_FRAME_BEACON_LIKE) {
        parse_beacon_frame(f->payload, f->len, f->rssi, f->channel);
    } else {
        handle_assoc_request(f);
    }
}





static void sniffer_worker_task(void *arg)
{
    sniffer_raw_frame_t *frame = malloc(sizeof(sniffer_raw_frame_t));
    if (frame == NULL) {
        ESP_LOGE(TAG, "sem memoria para o buffer da worker task");
        vTaskDelete(NULL);
        return;
    }

    for (;;) {
        if (xQueueReceive(s_frame_queue, frame, portMAX_DELAY) == pdTRUE) {
            sniffer_process_frame(frame);
        }
    }
}

static void wifi_sniffer_promisc_cb(void *buf, wifi_promiscuous_pkt_type_t type)
{
    if (type != WIFI_PKT_MGMT) return;

    const wifi_promiscuous_pkt_t *pkt = (const wifi_promiscuous_pkt_t *)buf;
    const uint8_t *payload = pkt->payload;
    uint16_t len = pkt->rx_ctrl.sig_len;




    uint8_t kind;
    uint16_t min_len;
    switch (payload[0] & 0xFC) {
        case 0x80:
        case 0x50: kind = SNIFFER_FRAME_BEACON_LIKE; min_len = 36; break;
        case 0x00: kind = SNIFFER_FRAME_ASSOC;       min_len = 30; break;
        case 0x20: kind = SNIFFER_FRAME_REASSOC;     min_len = 36; break;
        default: return;
    }
    if (len < min_len) return;

    uint8_t radio_channel = pkt->rx_ctrl.channel;
    if (radio_channel <= WIFI_SNIFFER_MAX_CHANNEL) s_rx_by_channel[radio_channel]++;

    sniffer_raw_frame_t frame;
    frame.len = len > WIFI_SNIFFER_MAX_FRAME_LEN ? WIFI_SNIFFER_MAX_FRAME_LEN : len;
    frame.rssi = pkt->rx_ctrl.rssi;
    frame.channel = radio_channel;
    frame.kind = kind;
    memcpy(frame.payload, payload, frame.len);

    if (kind != SNIFFER_FRAME_BEACON_LIKE) {



        memset(&frame.payload[10], 0, 6);
        if (kind == SNIFFER_FRAME_REASSOC && frame.len >= 34) memset(&frame.payload[28], 0, 6);
    }



    xQueueSend(s_frame_queue, &frame, 0);
}





static volatile bool s_hop_running = false;
static TaskHandle_t s_hop_task = NULL;


static uint32_t hop_dwell_ms(uint8_t channel)
{
    return (channel == 1 || channel == 6 || channel == 11) ? CONFIG_WARDRIVER_HOP_DWELL_MAIN_MS
                                                           : CONFIG_WARDRIVER_HOP_DWELL_MS;
}

static uint8_t hop_next_channel(uint8_t channel)
{
    if (channel < CONFIG_WARDRIVER_HOP_FIRST_CHANNEL || channel >= CONFIG_WARDRIVER_HOP_LAST_CHANNEL) {
        return CONFIG_WARDRIVER_HOP_FIRST_CHANNEL;
    }
    return channel + 1;
}


__attribute__((unused)) static uint32_t wifi_sniffer_hop_sweep_ms(void)
{
    uint32_t total = 0;
    for (int ch = CONFIG_WARDRIVER_HOP_FIRST_CHANNEL; ch <= CONFIG_WARDRIVER_HOP_LAST_CHANNEL; ch++) {
        total += hop_dwell_ms((uint8_t)ch);
    }
    return total;
}

__attribute__((unused)) static void wifi_sniffer_hop_task(void *arg)
{
    (void)arg;
    uint8_t channel = CONFIG_WARDRIVER_HOP_FIRST_CHANNEL;
    while (s_hop_running) {
        if (esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE) == ESP_OK) {
            s_stats.hop_switches++;
        } else {
            s_stats.hop_failures++;
        }
        vTaskDelay(pdMS_TO_TICKS(hop_dwell_ms(channel)));
        channel = hop_next_channel(channel);
    }
    s_hop_task = NULL;
    vTaskDelete(NULL);
}





esp_err_t wifi_sniffer_init(void)
{
    s_network_capacity = WIFI_SNIFFER_INITIAL_CAPACITY;
    s_networks = calloc(s_network_capacity, sizeof(wifi_sniffer_network_t));
    if (s_networks == NULL) return ESP_ERR_NO_MEM;

    s_table_mutex = xSemaphoreCreateMutex();
    if (s_table_mutex == NULL) return ESP_ERR_NO_MEM;



    s_seen_bloom = calloc(1, SEEN_BLOOM_BYTES);
    if (s_seen_bloom == NULL) {
        ESP_LOGW(TAG, "sem memoria para o filtro de contagem (%u bytes) — contagem de redes sera aproximada",
                 (unsigned)SEEN_BLOOM_BYTES);
    }

    s_frame_queue = xQueueCreate(WIFI_SNIFFER_QUEUE_LEN, sizeof(sniffer_raw_frame_t));
    if (s_frame_queue == NULL) return ESP_ERR_NO_MEM;

    if (xTaskCreate(sniffer_worker_task, "sniffer_worker", 4096, NULL, 5, NULL) != pdPASS) {
        return ESP_FAIL;
    }

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_NULL));
    ESP_ERROR_CHECK(esp_wifi_start());



    wifi_country_t country = {
        .cc = "BR", .schan = 1, .nchan = 13, .max_tx_power = 20, .policy = WIFI_COUNTRY_POLICY_MANUAL,
    };
    esp_err_t cret = esp_wifi_set_country(&country);
    if (cret != ESP_OK) {
        ESP_LOGW(TAG, "nao foi possivel fixar o pais/canais do radio (%s) — canais 12-13 podem nao ser sintonizaveis",
                 esp_err_to_name(cret));
    }

    ESP_LOGI(TAG, "wifi_sniffer inicializado (modo promiscuo, sem STA/AP)");
    return ESP_OK;
}

esp_err_t wifi_sniffer_start(uint8_t channel, wifi_sniffer_network_cb_t cb, void *ctx)
{
    s_user_cb = cb;
    s_user_ctx = ctx;

    wifi_promiscuous_filter_t filter = {
        .filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT,
    };
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_filter(&filter));
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous_rx_cb(wifi_sniffer_promisc_cb));
    ESP_ERROR_CHECK(esp_wifi_set_promiscuous(true));

#ifdef CONFIG_WARDRIVER_CHANNEL_HOPPING
    ESP_ERROR_CHECK(esp_wifi_set_channel(CONFIG_WARDRIVER_HOP_FIRST_CHANNEL, WIFI_SECOND_CHAN_NONE));
    s_hop_running = true;
    if (s_hop_task == NULL &&
        xTaskCreate(wifi_sniffer_hop_task, "wifi_hop", 3072, NULL, 4, &s_hop_task) != pdPASS) {
        s_hop_running = false;
        ESP_LOGE(TAG, "sem memoria para a task de channel hopping — usando o canal fixo %u", channel);
        ESP_ERROR_CHECK(esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE));
    } else {
        ESP_LOGI(TAG, "captura iniciada com channel hopping: canais %d-%d (%u ms em cada; %u ms em 1/6/11), volta completa ~%u ms",
                 CONFIG_WARDRIVER_HOP_FIRST_CHANNEL, CONFIG_WARDRIVER_HOP_LAST_CHANNEL,
                 (unsigned)CONFIG_WARDRIVER_HOP_DWELL_MS, (unsigned)CONFIG_WARDRIVER_HOP_DWELL_MAIN_MS,
                 (unsigned)wifi_sniffer_hop_sweep_ms());
    }
#else
    ESP_ERROR_CHECK(esp_wifi_set_channel(channel, WIFI_SECOND_CHAN_NONE));
    ESP_LOGI(TAG, "captura iniciada no canal %u (fixo)", channel);
#endif
    return ESP_OK;
}

esp_err_t wifi_sniffer_stop(void)
{
    s_hop_running = false;
    return esp_wifi_set_promiscuous(false);
}

uint32_t wifi_sniffer_get_unique_count(void)
{
    uint32_t count;
    xSemaphoreTake(s_table_mutex, portMAX_DELAY);
    count = s_seen_count;
    xSemaphoreGive(s_table_mutex);
    return count;
}

uint32_t wifi_sniffer_get_table_size(void)
{
    uint32_t size;
    xSemaphoreTake(s_table_mutex, portMAX_DELAY);
    size = (uint32_t)s_network_count;
    xSemaphoreGive(s_table_mutex);
    return size;
}

void wifi_sniffer_reset_session_count(void)
{
    xSemaphoreTake(s_table_mutex, portMAX_DELAY);
    if (s_seen_bloom != NULL) {
        memset(s_seen_bloom, 0, SEEN_BLOOM_BYTES);
    }
    s_seen_count = 0;
    xSemaphoreGive(s_table_mutex);
}

bool wifi_sniffer_note_known(const uint8_t bssid[6])
{
    xSemaphoreTake(s_table_mutex, portMAX_DELAY);
    bool was_new = seen_mark_locked(bssid);
    xSemaphoreGive(s_table_mutex);
    return was_new;
}





static void shrink_table_locked(void)
{
    int target = s_network_capacity;
    while (target / 2 >= WIFI_SNIFFER_INITIAL_CAPACITY && target / 2 >= s_network_count * 2) {
        target /= 2;
    }
    if (target < s_network_capacity) {
        wifi_sniffer_network_t *smaller = realloc(s_networks, target * sizeof(wifi_sniffer_network_t));
        if (smaller != NULL) {
            s_networks = smaller;
            s_network_capacity = target;
        }
    }
}

size_t wifi_sniffer_trim_table(uint32_t max_age_ms)
{
    const int64_t max_age_us = (int64_t)max_age_ms * 1000;
    const int64_t now = esp_timer_get_time();

    xSemaphoreTake(s_table_mutex, portMAX_DELAY);
    int kept = 0;
    for (int i = 0; i < s_network_count; i++) {
        if (now - s_networks[i].last_seen_us <= max_age_us) {
            if (kept != i) s_networks[kept] = s_networks[i];
            kept++;
        }
    }
    size_t dropped = (size_t)(s_network_count - kept);
    s_network_count = kept;
    int old_capacity = s_network_capacity;
    if (dropped > 0) shrink_table_locked();
    int new_capacity = s_network_capacity;
    xSemaphoreGive(s_table_mutex);

    if (dropped > 0) {
        ESP_LOGI(TAG, "tabela podada: %u entrada(s) sem sinal ha >%us removida(s), %d restam (capacidade %d -> %d)",
                 (unsigned)dropped, (unsigned)(max_age_ms / 1000), kept, old_capacity, new_capacity);
    }
    return dropped;
}

void wifi_sniffer_reset_table(void)
{
    xSemaphoreTake(s_table_mutex, portMAX_DELAY);
    int old_count = s_network_count;
    s_network_count = 0;
    shrink_table_locked();
    int new_capacity = s_network_capacity;
    xSemaphoreGive(s_table_mutex);
    ESP_LOGW(TAG, "tabela de deduplicacao zerada por completo (tinha %d rede(s); capacidade agora %d) — "
                  "nomes de SSID ja descobertos foram esquecidos", old_count, new_capacity);
}

void wifi_sniffer_get_stats(wifi_sniffer_stats_t *out)
{
    xSemaphoreTake(s_table_mutex, portMAX_DELAY);
    *out = s_stats;
    out->table_size = (uint32_t)s_network_count;
    out->unique_count = s_seen_count;
    out->table_unnamed = 0;
    for (int i = 0; i < s_network_count; i++) {
        if (s_networks[i].ssid_len == 0 || s_networks[i].ssid_hidden) out->table_unnamed++;
    }
    for (int ch = 0; ch <= WIFI_SNIFFER_MAX_CHANNEL; ch++) out->rx_by_channel[ch] = s_rx_by_channel[ch];
    xSemaphoreGive(s_table_mutex);
}
