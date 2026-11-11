#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "esp_err.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "nvs_flash.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "wardriver_record.h"
#include "wifi_sniffer.h"
#include "gps_nmea.h"
#include "phone_gps.h"
#include "flash_storage.h"
#include "oled_display.h"
#include "bt_spp_sync.h"

static const char *TAG = "wardriver";

static bool s_storage_mounted = false;
static volatile bool s_storage_ok = false;

#define STORAGE_RETRY_EVERY_CYCLES 5

#define THROTTLE_SETS 128
#define THROTTLE_WAYS 4

typedef struct __attribute__((packed)) {
    uint8_t bssid[6];
    uint32_t last_write_ms;
} log_throttle_entry_t;

static log_throttle_entry_t s_throttle[THROTTLE_SETS][THROTTLE_WAYS];

static bool bssid_is_zero(const uint8_t *bssid)
{
    static const uint8_t zero[6] = {0};
    return memcmp(bssid, zero, 6) == 0;
}

static bool log_throttle_should_write(const uint8_t *bssid, int64_t now_us)
{
    const uint32_t interval_ms = CONFIG_WARDRIVER_LOG_REWRITE_INTERVAL_MS;
    const uint32_t now_ms = (uint32_t)(now_us / 1000);

    uint32_t key = ((uint32_t)bssid[3] << 16) | ((uint32_t)bssid[4] << 8) | bssid[5];
    log_throttle_entry_t *set = s_throttle[(key * 2654435761u) >> 25];

    log_throttle_entry_t *victim = &set[0];
    for (int way = 0; way < THROTTLE_WAYS; way++) {
        if (memcmp(set[way].bssid, bssid, 6) == 0) {
            if ((uint32_t)(now_ms - set[way].last_write_ms) < interval_ms) {
                return false;
            }
            set[way].last_write_ms = now_ms;
            return true;
        }

        if (bssid_is_zero(set[way].bssid) ||
            (!bssid_is_zero(victim->bssid) &&
             (uint32_t)(now_ms - set[way].last_write_ms) > (uint32_t)(now_ms - victim->last_write_ms))) {
            victim = &set[way];
        }
    }

    memcpy(victim->bssid, bssid, 6);
    victim->last_write_ms = now_ms;
    return true;
}

static const char *encryption_to_str(wifi_sniffer_encryption_t enc)
{
    switch (enc) {
        case WIFI_SNIFFER_ENC_OPEN: return "OPEN";
        case WIFI_SNIFFER_ENC_WEP:  return "WEP";
        case WIFI_SNIFFER_ENC_WPA:  return "WPA";
        case WIFI_SNIFFER_ENC_WPA2: return "WPA2";
        case WIFI_SNIFFER_ENC_WPA3: return "WPA3";
        default: return "UNKNOWN";
    }
}

static esp_err_t rotate_session_and_reset_count(char *old_id_out, size_t old_len,
                                                 char *new_id_out, size_t new_len)
{
    esp_err_t ret = flash_storage_rotate_session(old_id_out, old_len, new_id_out, new_len);
    if (ret == ESP_OK) {
        wifi_sniffer_reset_session_count();
    }
    return ret;
}

typedef enum {
    GPS_SRC_NONE = 0,
    GPS_SRC_PHONE,
    GPS_SRC_MODULE,
} gps_source_t;

#define GPS_MODULE_FRESH_US (3LL * 1000000)
#define GPS_MODULE_UERE_M   5.0f
#define GPS_MODULE_NO_HDOP_M 20.0f
#define GPS_PHONE_NO_ACC_M   15.0f
#define GPS_SWITCH_MARGIN   0.7f

static gps_source_t s_gps_source = GPS_SRC_NONE;

static const char *gps_source_name(gps_source_t src)
{
    return src == GPS_SRC_PHONE ? "celular" : src == GPS_SRC_MODULE ? "modulo" : "nenhuma";
}

static oled_gps_source_t gps_source_for_oled(gps_source_t src)
{
    return src == GPS_SRC_PHONE ? OLED_GPS_PHONE : src == GPS_SRC_MODULE ? OLED_GPS_MODULE : OLED_GPS_NONE;
}

static gps_source_t get_best_gps_data(gps_data_t *out)
{
    gps_data_t phone, mod;
    bool have_phone = phone_gps_get_data(&phone);
    gps_nmea_get_data(&mod);
    bool have_mod = mod.has_fix && mod.last_update_us > 0 &&
                    (esp_timer_get_time() - mod.last_update_us) < GPS_MODULE_FRESH_US;

    gps_source_t pick;
    if (have_phone && have_mod) {
        float phone_err = phone.hdop > 0.0f ? phone.hdop : GPS_PHONE_NO_ACC_M;
        float mod_err = (mod.hdop > 0.0f ? mod.hdop * GPS_MODULE_UERE_M : GPS_MODULE_NO_HDOP_M);
        if (s_gps_source == GPS_SRC_MODULE) {
            pick = (phone_err < mod_err * GPS_SWITCH_MARGIN) ? GPS_SRC_PHONE : GPS_SRC_MODULE;
        } else {
            pick = (mod_err < phone_err * GPS_SWITCH_MARGIN) ? GPS_SRC_MODULE : GPS_SRC_PHONE;
        }
    } else if (have_phone) {
        pick = GPS_SRC_PHONE;
    } else if (have_mod) {
        pick = GPS_SRC_MODULE;
    } else {
        pick = GPS_SRC_NONE;
    }
    s_gps_source = pick;

    if (pick == GPS_SRC_PHONE) {
        *out = phone;
    } else {
        *out = mod;
        if (pick == GPS_SRC_NONE) out->has_fix = false;
    }
    return pick;
}

static void on_beacon(const wifi_sniffer_network_t *net, bool is_new, bool ssid_just_revealed, void *ctx)
{
    (void)ctx;

    if (!log_throttle_should_write(net->bssid, esp_timer_get_time()) && !ssid_just_revealed) {
        return;
    }

    gps_data_t gps;
    (void)get_best_gps_data(&gps);

    wardriver_record_t rec = {0};
    memcpy(rec.bssid, net->bssid, sizeof(rec.bssid));
    strncpy(rec.ssid, net->ssid, sizeof(rec.ssid) - 1);

    rec.flags = (net->ssid_hidden ? WARDRIVER_FLAG_SSID_HIDDEN : 0) |
                (gps.has_fix ? WARDRIVER_FLAG_HAS_FIX : 0) |
                (net->wps.present ? WARDRIVER_FLAG_WPS_PRESENT : 0) |
                (net->wps.configured ? WARDRIVER_FLAG_WPS_CONFIGURED : 0) |
                (net->wps.locked ? WARDRIVER_FLAG_WPS_LOCKED : 0);
    rec.channel = net->channel;
    rec.rssi = net->rssi;
    rec.encryption = (uint8_t)net->encryption;
    rec.times_seen = net->times_seen;
    rec.wps_config_methods = net->wps.config_methods;

    if (gps.has_fix) {
        rec.lat_e7 = (int32_t)(gps.latitude * 1e7);
        rec.lon_e7 = (int32_t)(gps.longitude * 1e7);
        rec.alt_m = (int16_t)gps.altitude_m;
        rec.hdop_x100 = (uint16_t)(gps.hdop * 100);
        rec.utc_hhmmss = (uint32_t)atoi(gps.utc_time);
    }
    rec.gps_quality = gps.fix_quality;
    rec.satellites = gps.satellites;
    rec.uptime_s = (uint32_t)(esp_timer_get_time() / 1000000);

    esp_err_t write_ret = s_storage_ok ? flash_storage_write_record(&rec, sizeof(rec))
                                        : ESP_ERR_INVALID_STATE;
    if (write_ret != ESP_OK) {

        static int64_t s_last_write_fail_log_us = 0;
        int64_t now_us = esp_timer_get_time();
        if (now_us - s_last_write_fail_log_us > 10LL * 1000000) {
            ESP_LOGE(TAG, "FALHA AO GRAVAR registro no storage (sem espaco?) — capturas estao sendo perdidas!");
            s_last_write_fail_log_us = now_us;
        }
    }
#if CONFIG_WARDRIVER_BT_ENABLED

    bt_spp_sync_stream_record(&rec, sizeof(rec));
#endif

    if (is_new || ssid_just_revealed) {
        char bssid_str[18];
        snprintf(bssid_str, sizeof(bssid_str), "%02X:%02X:%02X:%02X:%02X:%02X",
                 net->bssid[0], net->bssid[1], net->bssid[2],
                 net->bssid[3], net->bssid[4], net->bssid[5]);
        ESP_LOGI(TAG, "%s: %s ssid=\"%s\" ch=%u rssi=%d enc=%s wps=%d",
                 is_new ? "nova rede" : "SSID revelado", bssid_str, net->ssid, net->channel, net->rssi,
                 encryption_to_str(net->encryption), net->wps.present);
    }
}

static const char *reset_reason_str(esp_reset_reason_t reason)
{
    switch (reason) {
        case ESP_RST_POWERON:   return "POWERON (energia religada)";
        case ESP_RST_SW:        return "SW (reinicio por software)";
        case ESP_RST_PANIC:     return "PANIC (crash — ver backtrace acima)";
        case ESP_RST_INT_WDT:   return "INT_WDT (watchdog de interrupcao)";
        case ESP_RST_TASK_WDT:  return "TASK_WDT (watchdog de task)";
        case ESP_RST_WDT:       return "WDT (watchdog)";
        case ESP_RST_BROWNOUT:  return "BROWNOUT (tensao caiu abaixo do limite — alimentacao instavel)";
        case ESP_RST_DEEPSLEEP: return "DEEPSLEEP";
        case ESP_RST_EXT:       return "EXT (pino de reset)";
        default:                return "desconhecido";
    }
}

static void seed_count_task(void *arg)
{
    (void)arg;
    const char *root = CONFIG_WARDRIVER_FLASH_STORAGE_MOUNT_POINT;
    DIR *dir = opendir(root);
    if (dir == NULL) {
        vTaskDelete(NULL);
        return;
    }

    uint32_t files = 0, records = 0, counted = 0;
    wardriver_record_t batch[8];
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        size_t name_len = strlen(ent->d_name);
        if (name_len < 5 || strcmp(&ent->d_name[name_len - 4], ".wdr") != 0) {
            continue;
        }
        char path[320];
        snprintf(path, sizeof(path), "%s/%s", root, ent->d_name);
        FILE *f = fopen(path, "rb");
        if (f == NULL) {
            continue;
        }
        files++;
        size_t got;
        while ((got = fread(batch, sizeof(batch[0]), sizeof(batch) / sizeof(batch[0]), f)) > 0) {
            for (size_t i = 0; i < got; i++) {
                static const uint8_t zero[6] = {0};
                if (memcmp(batch[i].bssid, zero, 6) == 0) continue;
                records++;
                if (wifi_sniffer_note_known(batch[i].bssid)) counted++;
            }
            vTaskDelay(1);
        }
        fclose(f);
    }
    closedir(dir);

    ESP_LOGI(TAG, "contagem restaurada do storage: %u arquivo(s), %u registro(s), %u rede(s) unica(s) nova(s) no contador",
             (unsigned)files, (unsigned)records, (unsigned)counted);
    vTaskDelete(NULL);
}

void app_main(void)
{
    ESP_LOGI(TAG, "wardriver iniciando...");

    esp_reset_reason_t rst = esp_reset_reason();
    if (rst == ESP_RST_BROWNOUT) {
        ESP_LOGW(TAG, "motivo do ultimo reset: %s", reset_reason_str(rst));
    } else {
        ESP_LOGI(TAG, "motivo do ultimo reset: %s", reset_reason_str(rst));
    }

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());

    ESP_ERROR_CHECK(phone_gps_init());

    static char session_id[32] = "sem_storage";
    s_storage_mounted = (flash_storage_init() == ESP_OK);
    if (s_storage_mounted && flash_storage_start_session(session_id, sizeof(session_id)) == ESP_OK) {
        s_storage_ok = true;
    } else if (s_storage_mounted) {
        session_id[0] = '\0';
        ESP_LOGE(TAG, "memoria interna montada, mas nao foi possivel abrir a sessao (cheia?) — as capturas NAO "
                      "estao sendo gravadas; sincronize pelo app para liberar espaco (nova tentativa a cada %d s)",
                 STORAGE_RETRY_EVERY_CYCLES * CONFIG_WARDRIVER_STATUS_UPDATE_INTERVAL_MS / 1000);
    } else {
        ESP_LOGE(TAG, "memoria interna indisponivel — capturas nao serao persistidas");
    }

#if CONFIG_WARDRIVER_OLED_ENABLED

    if (oled_display_init() != ESP_OK) {
        ESP_LOGW(TAG, "display OLED nao respondeu no boot — o guardiao continua tentando");
    }
    if (oled_display_start_guard() != ESP_OK) {
        ESP_LOGW(TAG, "nao foi possivel iniciar o guardiao do OLED (sem memoria)");
    }
#endif

#if CONFIG_WARDRIVER_BT_ENABLED
    bt_spp_sync_set_storage_root(s_storage_mounted ? CONFIG_WARDRIVER_FLASH_STORAGE_MOUNT_POINT : "");
    bt_spp_sync_set_current_session(s_storage_ok ? session_id : "");
    if (bt_spp_sync_init() != ESP_OK) {
        ESP_LOGW(TAG, "sincronizacao Bluetooth indisponivel");
    } else if (s_storage_mounted) {

        bt_spp_sync_set_rotate_backend(rotate_session_and_reset_count, session_id, sizeof(session_id));
    }
#endif

    ESP_ERROR_CHECK(gps_nmea_init());
    ESP_ERROR_CHECK(gps_nmea_start());

    ESP_ERROR_CHECK(wifi_sniffer_init());

    ESP_ERROR_CHECK(wifi_sniffer_start(CONFIG_WARDRIVER_WIFI_CHANNEL, on_beacon, NULL));

    ESP_LOGI(TAG, "captura ativa — sessao=%s canal=%d storage=%s",
             session_id, CONFIG_WARDRIVER_WIFI_CHANNEL, s_storage_ok ? "memoria interna" : "nenhum");

    if (s_storage_mounted && xTaskCreate(seed_count_task, "seed_count", 4096, NULL, 1, NULL) != pdPASS) {
        ESP_LOGW(TAG, "nao foi possivel iniciar a restauracao da contagem (sem memoria)");
    }

    unsigned status_cycle = 0;
    while (1) {
        vTaskDelay(pdMS_TO_TICKS(CONFIG_WARDRIVER_STATUS_UPDATE_INTERVAL_MS));
        status_cycle++;

        if (s_storage_mounted && !s_storage_ok && status_cycle % STORAGE_RETRY_EVERY_CYCLES == 0 &&
            flash_storage_start_session(session_id, sizeof(session_id)) == ESP_OK) {
            bt_spp_sync_set_current_session(session_id);
            s_storage_ok = true;
            ESP_LOGI(TAG, "espaco liberado — sessao %s aberta, capturas voltaram a ser gravadas", session_id);
        }

        gps_data_t gps;
        gps_nmea_get_data(&gps);
        gps_data_t best_gps;
        gps_source_t source = get_best_gps_data(&best_gps);
        bool mod_fresh = gps.last_update_us > 0 &&
                         (esp_timer_get_time() - gps.last_update_us) < GPS_MODULE_FRESH_US;
        bool mod_fix = gps.has_fix && mod_fresh;

        static bool s_prev_mod_fix = false;
        static gps_source_t s_prev_source = GPS_SRC_NONE;
        static bool s_mod_ever_fixed = false;
        if (mod_fix != s_prev_mod_fix) {
            if (mod_fix) {
                ESP_LOGI(TAG, "GPS do modulo: FIX %s apos %llu s de boot (sats=%u hdop=%.1f)",
                         s_mod_ever_fixed ? "recuperado" : "obtido pela 1a vez",
                         (unsigned long long)(esp_timer_get_time() / 1000000), gps.satellites, gps.hdop);
                s_mod_ever_fixed = true;
            } else {
                ESP_LOGW(TAG, "GPS do modulo: fix PERDIDO (sats_view=%u sats_tracked=%u)",
                         gps.sats_in_view, gps.sats_tracked);
            }
            s_prev_mod_fix = mod_fix;
        }
        if (source != s_prev_source) {
            ESP_LOGI(TAG, "fonte de posicao: %s -> %s", gps_source_name(s_prev_source), gps_source_name(source));
            s_prev_source = source;
        }

        uint32_t networks = wifi_sniffer_get_unique_count();

        uint32_t heap_free = (uint32_t)esp_get_free_heap_size();
        if (heap_free < CONFIG_WARDRIVER_DEDUP_RESET_HEAP_FLOOR_BYTES) {
            ESP_LOGW(TAG, "heap livre (%u) abaixo do piso de seguranca (%d) — podando tabela de deduplicacao",
                     (unsigned)heap_free, CONFIG_WARDRIVER_DEDUP_RESET_HEAP_FLOOR_BYTES);
            uint32_t age_ms = (uint32_t)CONFIG_WARDRIVER_DEDUP_STALE_SECONDS * 1000;
            wifi_sniffer_trim_table(age_ms);
            if ((uint32_t)esp_get_free_heap_size() < CONFIG_WARDRIVER_DEDUP_RESET_HEAP_FLOOR_BYTES) {
                wifi_sniffer_trim_table(age_ms / 4);
            }
            if ((uint32_t)esp_get_free_heap_size() < CONFIG_WARDRIVER_DEDUP_RESET_HEAP_FLOOR_BYTES) {
                wifi_sniffer_reset_table();
            }
        }

        if (status_cycle % 15 == 0) {
            wifi_sniffer_stats_t st;
            wifi_sniffer_get_stats(&st);
            ESP_LOGI(TAG, "ssid: tabela=%u sem_nome=%u | frames: beacon=%u probe_resp=%u | em_branco: tam0=%u zeros=%u | "
                          "revelados: probe=%u beacon=%u | nome_preservado=%u | falhas_alloc=%u",
                     (unsigned)st.table_size, (unsigned)st.table_unnamed,
                     (unsigned)st.beacons, (unsigned)st.probe_responses,
                     (unsigned)st.ssid_null_len, (unsigned)st.ssid_zero_filled,
                     (unsigned)st.names_revealed_probe, (unsigned)st.names_revealed_beacon,
                     (unsigned)st.name_kept, (unsigned)st.alloc_failures);

            char ch_buf[192];
            size_t ch_n = 0;
            ch_buf[0] = '\0';
            for (int ch = 1; ch <= 13 && ch_n < sizeof(ch_buf) - 16; ch++) {
                ch_n += snprintf(ch_buf + ch_n, sizeof(ch_buf) - ch_n, "%s%d:%u", ch_n ? "," : "", ch, (unsigned)st.rx_by_channel[ch]);
            }
            ESP_LOGI(TAG, "canais: %s | trocas=%u recusadas=%u | assoc_req=%u nomes_por_assoc=%u",
                     ch_buf, (unsigned)st.hop_switches, (unsigned)st.hop_failures,
                     (unsigned)st.assoc_requests, (unsigned)st.names_revealed_assoc);

            oled_stats_t ost = {0};
#if CONFIG_WARDRIVER_OLED_ENABLED
            oled_display_get_stats(&ost);
            ESP_LOGI(TAG, "oled: ativo=%d quadros_ok=%u falhas=%u seguidas=%u guardiao=%u reinit_frio=%u recuperacoes=%u init_retries=%u ultimo_ok_ha=%lldms",
                     ost.initialized, (unsigned)ost.pushes_ok, (unsigned)ost.pushes_failed, (unsigned)ost.consecutive_failures,
                     (unsigned)ost.guard_pushes, (unsigned)ost.full_reinits, (unsigned)ost.bus_recoveries,
                     (unsigned)ost.init_retries, (long long)ost.last_ok_age_ms);
#endif

#if CONFIG_WARDRIVER_BT_ENABLED

            static char stats_line[720];
            snprintf(stats_line, sizeof(stats_line),
                     "STATS t=%u heap=%u redes=%u tabela=%u sem_nome=%u beacon=%u probe=%u tam0=%u zeros=%u "
                     "rev_probe=%u rev_beacon=%u preservado=%u falhas_alloc=%u gps=%s mod_fix=%d mod_sats=%u "
                     "mod_view=%u mod_trk=%u mod_snr=%d mod_hdop=%.1f "
                     "oled_up=%d oled_ok=%u oled_fail=%u oled_rec=%u oled_reinit=%u "
                     "assoc=%u rev_assoc=%u hops=%u hopfail=%u ch=%s",
                     (unsigned)(esp_timer_get_time() / 1000000), (unsigned)esp_get_free_heap_size(),
                     (unsigned)networks, (unsigned)st.table_size, (unsigned)st.table_unnamed,
                     (unsigned)st.beacons, (unsigned)st.probe_responses,
                     (unsigned)st.ssid_null_len, (unsigned)st.ssid_zero_filled,
                     (unsigned)st.names_revealed_probe, (unsigned)st.names_revealed_beacon,
                     (unsigned)st.name_kept, (unsigned)st.alloc_failures,
                     gps_source_name(source), mod_fix, gps.satellites,
                     gps.sats_in_view, gps.sats_tracked, gps.best_snr_db, gps.hdop,
                     ost.initialized, (unsigned)ost.pushes_ok, (unsigned)ost.pushes_failed,
                     (unsigned)ost.bus_recoveries, (unsigned)ost.full_reinits,
                     (unsigned)st.assoc_requests, (unsigned)st.names_revealed_assoc,
                     (unsigned)st.hop_switches, (unsigned)st.hop_failures, ch_buf);
            bt_spp_sync_send_status_line(stats_line);
#endif
        }

        int storage_percent = -1;
        if (s_storage_ok) {
            size_t used = 0, total = 0;
            if (flash_storage_get_usage(&used, &total) == ESP_OK && total > 0) {
                storage_percent = (int)((used * 100) / total);
            }
        }

        ESP_LOGI(TAG, "redes=%lu fonte=%s mod_fix=%d mod_sats=%u mod_view=%u mod_trk=%u mod_snr=%d lat=%.6f lon=%.6f mem=%d%% heap_livre=%u",
                 (unsigned long)networks, gps_source_name(source), mod_fix, gps.satellites,
                 gps.sats_in_view, gps.sats_tracked, gps.best_snr_db,
                 best_gps.latitude, best_gps.longitude, storage_percent,
                 (unsigned)esp_get_free_heap_size());

#if CONFIG_WARDRIVER_OLED_ENABLED
        {

            uint8_t mod_sats_shown = mod_fix ? gps.satellites : (mod_fresh ? gps.sats_tracked : 0);
            esp_err_t oled_ret = oled_display_show_status(networks, mod_sats_shown, gps_source_for_oled(source),
                                                            s_storage_ok, storage_percent);

            static bool s_oled_was_failing = false;
            static unsigned s_oled_fail_cycles = 0;
            if (oled_ret != ESP_OK) {
                if (s_oled_fail_cycles++ % 30 == 0) {
                    ESP_LOGW(TAG, "falha ao atualizar o display OLED: %s (%u ciclo(s) seguidos)",
                             esp_err_to_name(oled_ret), s_oled_fail_cycles);
                }
                s_oled_was_failing = true;
            } else if (s_oled_was_failing) {
                ESP_LOGI(TAG, "display OLED voltou a responder (apos %u ciclo(s) com falha)", s_oled_fail_cycles);
                s_oled_was_failing = false;
                s_oled_fail_cycles = 0;
            }
        }
#endif
        if (s_storage_ok) {
            flash_storage_flush();
        }
    }
}
