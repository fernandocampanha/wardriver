#include "oled_display.h"
#include "font5x7.h"

#include <string.h>
#include <stdio.h>

#include "driver/i2c.h"
#include "driver/gpio.h"
#include "esp_rom_sys.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "sdkconfig.h"

#ifndef CONFIG_WARDRIVER_OLED_FULL_REINIT_INTERVAL_S
#define CONFIG_WARDRIVER_OLED_FULL_REINIT_INTERVAL_S 300
#endif

static const char *TAG = "oled_display";

#define OLED_I2C_PORT I2C_NUM_0

#define OLED_MAX_CONSECUTIVE_FAILURES 3

#define OLED_BOOT_INIT_ATTEMPTS 3

#define OLED_INIT_RETRY_US (5LL * 1000000)

#define OLED_GUARD_PERIOD_MS 1000

static uint8_t s_framebuf[OLED_PAGES][OLED_WIDTH];
static SemaphoreHandle_t s_lock = NULL;
static bool s_driver_installed = false;
static bool s_initialized = false;
static bool s_heartbeat_on = false;
static TaskHandle_t s_guard_task = NULL;

static int s_consecutive_failures = 0;
static int64_t s_last_ok_us = 0;
static int64_t s_last_full_us = 0;
static int64_t s_next_init_try_us = 0;

static oled_stats_t s_stats;

static const uint8_t s_cfg_cmds[] = {
    0xD5, 0x80,
    0xA8, 0x3F,
    0xD3, 0x00,
    0x40,
    0x8D, 0x14,
    0x20, 0x00,
    0xA1,
    0xC8,
    0xDA, 0x12,
    0x81, 0xCF,
    0xD9, 0xF1,
    0xDB, 0x40,
    0xA4,
    0xA6,
    0xAF,
};

static void oled_lock(void)
{
    if (s_lock != NULL) xSemaphoreTakeRecursive(s_lock, portMAX_DELAY);
}

static void oled_unlock(void)
{
    if (s_lock != NULL) xSemaphoreGiveRecursive(s_lock);
}

static esp_err_t oled_write_cmd(uint8_t cmd)
{
    uint8_t buf[2] = {0x00, cmd};
    return i2c_master_write_to_device(OLED_I2C_PORT, CONFIG_WARDRIVER_OLED_I2C_ADDR,
                                       buf, sizeof(buf), pdMS_TO_TICKS(100));
}

static esp_err_t oled_write_nops(void)
{
    static const uint8_t buf[4] = {0x00, 0xE3, 0xE3, 0xE3};
    return i2c_master_write_to_device(OLED_I2C_PORT, CONFIG_WARDRIVER_OLED_I2C_ADDR,
                                       buf, sizeof(buf), pdMS_TO_TICKS(100));
}

static esp_err_t oled_apply_cfg(bool cold)
{
    esp_err_t ret = oled_write_nops();
    if (ret != ESP_OK) return ret;

    if (cold) {
        static const uint8_t off_cmds[] = {0xAE, 0x8D, 0x10};
        for (size_t i = 0; i < sizeof(off_cmds); i++) {
            ret = oled_write_cmd(off_cmds[i]);
            if (ret != ESP_OK) return ret;
        }
    }
    for (size_t i = 0; i < sizeof(s_cfg_cmds); i++) {
        ret = oled_write_cmd(s_cfg_cmds[i]);
        if (ret != ESP_OK) return ret;
    }
    return ESP_OK;
}

static esp_err_t oled_set_full_window(void)
{
    static const uint8_t window_cmds[] = {
        0x21, 0x00, OLED_WIDTH - 1,
        0x22, 0x00, OLED_PAGES - 1,
    };
    for (size_t i = 0; i < sizeof(window_cmds); i++) {
        esp_err_t ret = oled_write_cmd(window_cmds[i]);
        if (ret != ESP_OK) return ret;
    }
    return ESP_OK;
}

static esp_err_t oled_write_pages(void)
{
    for (uint8_t page = 0; page < OLED_PAGES; page++) {
        uint8_t buf[OLED_WIDTH + 1];
        buf[0] = 0x40;
        memcpy(&buf[1], s_framebuf[page], OLED_WIDTH);
        esp_err_t ret = i2c_master_write_to_device(OLED_I2C_PORT, CONFIG_WARDRIVER_OLED_I2C_ADDR,
                                                    buf, sizeof(buf), pdMS_TO_TICKS(200));
        if (ret != ESP_OK) return ret;
    }
    return ESP_OK;
}

static const font5x7_glyph_t *font5x7_find(char c)
{
    if (c >= 'a' && c <= 'z') c -= ('a' - 'A');
    for (int i = 0; i < font5x7_table_len; i++) {
        if (font5x7_table[i].c == c) return &font5x7_table[i];
    }
    return NULL;
}

void oled_display_clear(void)
{
    oled_lock();
    memset(s_framebuf, 0, sizeof(s_framebuf));
    oled_unlock();
}

void oled_display_draw_string(uint8_t x, uint8_t page, const char *str)
{
    if (page >= OLED_PAGES) return;

    oled_lock();
    while (*str != '\0' && x < OLED_WIDTH) {
        const font5x7_glyph_t *glyph = font5x7_find(*str);
        for (int col = 0; col < 5 && x < OLED_WIDTH; col++, x++) {
            s_framebuf[page][x] = glyph != NULL ? glyph->cols[col] : 0x00;
        }
        if (x < OLED_WIDTH) {
            s_framebuf[page][x++] = 0x00;
        }
        str++;
    }
    oled_unlock();
}

static void oled_apply_heartbeat(void)
{
#ifdef CONFIG_WARDRIVER_OLED_HEARTBEAT
    s_heartbeat_on = ((esp_timer_get_time() / 1000000) & 1) != 0;
    for (int x = OLED_WIDTH - 6; x < OLED_WIDTH; x++) {
        s_framebuf[0][x] = s_heartbeat_on ? 0x0F : 0x00;
    }
#endif
}

typedef struct {
    bool sda_low_before;
    bool scl_low_before;
    bool scl_low_after;
} bus_diag_t;

static bus_diag_t oled_i2c_bus_recover_pins(void)
{
    bus_diag_t d = {0};
    gpio_config_t io_conf = {
        .pin_bit_mask = (1ULL << CONFIG_WARDRIVER_OLED_SDA_GPIO) |
                         (1ULL << CONFIG_WARDRIVER_OLED_SCL_GPIO),
        .mode = GPIO_MODE_INPUT_OUTPUT_OD,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&io_conf);

    gpio_set_level(CONFIG_WARDRIVER_OLED_SDA_GPIO, 1);
    gpio_set_level(CONFIG_WARDRIVER_OLED_SCL_GPIO, 1);
    esp_rom_delay_us(5);
    d.sda_low_before = gpio_get_level(CONFIG_WARDRIVER_OLED_SDA_GPIO) == 0;
    d.scl_low_before = gpio_get_level(CONFIG_WARDRIVER_OLED_SCL_GPIO) == 0;

    for (int i = 0; i < 9 && gpio_get_level(CONFIG_WARDRIVER_OLED_SDA_GPIO) == 0; i++) {
        gpio_set_level(CONFIG_WARDRIVER_OLED_SCL_GPIO, 0);
        esp_rom_delay_us(5);
        gpio_set_level(CONFIG_WARDRIVER_OLED_SCL_GPIO, 1);
        esp_rom_delay_us(5);
    }

    gpio_set_level(CONFIG_WARDRIVER_OLED_SDA_GPIO, 0);
    esp_rom_delay_us(5);
    gpio_set_level(CONFIG_WARDRIVER_OLED_SCL_GPIO, 1);
    esp_rom_delay_us(5);
    gpio_set_level(CONFIG_WARDRIVER_OLED_SDA_GPIO, 1);
    esp_rom_delay_us(5);
    d.scl_low_after = gpio_get_level(CONFIG_WARDRIVER_OLED_SCL_GPIO) == 0;
    return d;
}

static void oled_drop_driver(void)
{
    if (s_driver_installed) {
        i2c_driver_delete(OLED_I2C_PORT);
        s_driver_installed = false;
    }
}

static esp_err_t oled_send_frame(void)
{
    esp_err_t ret = oled_set_full_window();
    if (ret == ESP_OK) ret = oled_write_pages();
    return ret;
}

static esp_err_t oled_bringup_locked(int attempts)
{
    esp_err_t ret = ESP_FAIL;
    for (int attempt = 0; attempt < attempts; attempt++) {
        if (!s_driver_installed) {

            (void)oled_i2c_bus_recover_pins();
            i2c_config_t conf = {
                .mode = I2C_MODE_MASTER,
                .sda_io_num = CONFIG_WARDRIVER_OLED_SDA_GPIO,
                .scl_io_num = CONFIG_WARDRIVER_OLED_SCL_GPIO,
                .sda_pullup_en = GPIO_PULLUP_ENABLE,
                .scl_pullup_en = GPIO_PULLUP_ENABLE,
                .master.clk_speed = 400000,
            };
            ret = i2c_param_config(OLED_I2C_PORT, &conf);
            if (ret == ESP_OK) ret = i2c_driver_install(OLED_I2C_PORT, conf.mode, 0, 0, 0);
            if (ret != ESP_OK) {
                ESP_LOGE(TAG, "falha ao instalar o driver I2C: %s", esp_err_to_name(ret));
                vTaskDelay(pdMS_TO_TICKS(50));
                continue;
            }
            s_driver_installed = true;
        }

        vTaskDelay(pdMS_TO_TICKS(20));
        ret = oled_apply_cfg(true);
        if (ret == ESP_OK) ret = oled_send_frame();
        if (ret == ESP_OK) {
            int64_t now = esp_timer_get_time();
            s_initialized = true;
            s_last_ok_us = s_last_full_us = now;
            s_consecutive_failures = 0;
            return ESP_OK;
        }

        ESP_LOGW(TAG, "init do OLED falhou (tentativa %d/%d): %s. Confira o endereco I2C (0x3C vs 0x3D) e o GND.",
                 attempt + 1, attempts, esp_err_to_name(ret));
        oled_drop_driver();
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    s_initialized = false;
    return ret;
}

static esp_err_t oled_recover_locked(void)
{
    s_stats.bus_recoveries++;
    oled_drop_driver();
    bus_diag_t d = oled_i2c_bus_recover_pins();
    ESP_LOGW(TAG, "recuperando o barramento do OLED (%d falhas seguidas; antes do bus clear: SDA=%s SCL=%s)",
             s_consecutive_failures, d.sda_low_before ? "PRESO EM BAIXO" : "ok", d.scl_low_before ? "PRESO EM BAIXO" : "ok");
    if (d.scl_low_after) {
        ESP_LOGE(TAG, "SCL continua em nivel baixo apos o bus clear — nao se resolve por software: "
                      "confira solda/curto/modulo (o escravo esta segurando o clock)");
    }

    esp_err_t ret = oled_bringup_locked(1);
    if (ret == ESP_OK) {
        ESP_LOGI(TAG, "OLED recuperado");
    } else {

        s_next_init_try_us = esp_timer_get_time() + OLED_INIT_RETRY_US;
        ESP_LOGE(TAG, "OLED nao voltou (%s); nova tentativa em %d s", esp_err_to_name(ret), (int)(OLED_INIT_RETRY_US / 1000000));
    }
    return ret;
}

static esp_err_t oled_push_locked(bool cold)
{
    oled_apply_heartbeat();

    esp_err_t ret = oled_apply_cfg(cold);
    if (ret == ESP_OK) ret = oled_send_frame();

    int64_t now = esp_timer_get_time();
    if (ret == ESP_OK) {
        s_last_ok_us = now;
        s_consecutive_failures = 0;
        s_stats.pushes_ok++;
        if (cold) {
            s_last_full_us = now;
            s_stats.full_reinits++;
        }
        return ESP_OK;
    }

    s_stats.pushes_failed++;
    s_consecutive_failures++;
    if (s_consecutive_failures >= OLED_MAX_CONSECUTIVE_FAILURES) {
        return oled_recover_locked();
    }
    return ret;
}

static esp_err_t oled_try_bringup_locked(void)
{
    int64_t now = esp_timer_get_time();
    if (now < s_next_init_try_us) return ESP_ERR_INVALID_STATE;
    s_stats.init_retries++;
    esp_err_t ret = oled_bringup_locked(1);
    if (ret != ESP_OK) s_next_init_try_us = esp_timer_get_time() + OLED_INIT_RETRY_US;
    else ESP_LOGI(TAG, "OLED inicializado numa nova tentativa (depois do boot)");
    return ret;
}

esp_err_t oled_display_init(void)
{
    if (s_lock == NULL) {
        s_lock = xSemaphoreCreateRecursiveMutex();
        if (s_lock == NULL) return ESP_ERR_NO_MEM;
    }
    oled_lock();
    memset(s_framebuf, 0, sizeof(s_framebuf));
    esp_err_t ret = oled_bringup_locked(OLED_BOOT_INIT_ATTEMPTS);
    if (ret != ESP_OK) {

        ESP_LOGW(TAG, "OLED nao respondeu no boot (%s) — vou tentar de novo a cada %d s", esp_err_to_name(ret),
                 (int)(OLED_INIT_RETRY_US / 1000000));
        s_next_init_try_us = esp_timer_get_time() + OLED_INIT_RETRY_US;
    }
    oled_unlock();
    return ret;
}

esp_err_t oled_display_update(void)
{
    oled_lock();
    esp_err_t ret;
    if (!s_initialized) {

        ret = (s_guard_task == NULL) ? oled_try_bringup_locked() : ESP_ERR_INVALID_STATE;
    } else {
        ret = oled_push_locked(false);
    }
    oled_unlock();
    return ret;
}

esp_err_t oled_display_show_status(uint32_t networks, uint8_t satellites,
                                    oled_gps_source_t gps_source, bool storage_ok, int storage_percent)
{
    char line[48];

    oled_lock();
    oled_display_clear();
    oled_display_draw_string(0, 0, "WARDRIVER");

    snprintf(line, sizeof(line), "REDES: %lu", (unsigned long)networks);
    oled_display_draw_string(0, 2, line);

    const char *gps_label = gps_source == OLED_GPS_PHONE ? "CEL" :
                            gps_source == OLED_GPS_MODULE ? "MOD" : "--";
    snprintf(line, sizeof(line), "GPS:%s SATS:%u", gps_label, satellites);
    oled_display_draw_string(0, 4, line);

    if (storage_percent >= 0) {
        snprintf(line, sizeof(line), "MEM: %d%% USADA", storage_percent);
        oled_display_draw_string(0, 6, line);
    } else {
        oled_display_draw_string(0, 6, storage_ok ? "MEM: --" : "MEM: FALHA");
    }

    esp_err_t ret = oled_display_update();
    oled_unlock();
    return ret;
}

esp_err_t oled_display_show_message(const char *line1, const char *line2)
{
    oled_lock();
    oled_display_clear();
    oled_display_draw_string(0, 0, "WARDRIVER");
    if (line1 != NULL) oled_display_draw_string(0, 3, line1);
    if (line2 != NULL) oled_display_draw_string(0, 5, line2);
    esp_err_t ret = oled_display_update();
    oled_unlock();
    return ret;
}

void oled_display_get_stats(oled_stats_t *out)
{
    oled_lock();
    *out = s_stats;
    out->initialized = s_initialized;
    out->consecutive_failures = (uint32_t)s_consecutive_failures;
    out->last_ok_age_ms = s_last_ok_us > 0 ? (int64_t)((esp_timer_get_time() - s_last_ok_us) / 1000) : -1;
    oled_unlock();
}

static void oled_guard_tick(void)
{
    oled_lock();
    int64_t now = esp_timer_get_time();

    if (!s_initialized) {
        (void)oled_try_bringup_locked();
    } else {
        const int64_t full_interval_us = (int64_t)CONFIG_WARDRIVER_OLED_FULL_REINIT_INTERVAL_S * 1000000;
        bool full_due = full_interval_us > 0 && (now - s_last_full_us) >= full_interval_us;
        s_stats.guard_pushes++;
        (void)oled_push_locked(full_due);
    }
    oled_unlock();
}

static void oled_guard_task(void *arg)
{
    (void)arg;
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(OLED_GUARD_PERIOD_MS));
        oled_guard_tick();
    }
}

esp_err_t oled_display_start_guard(void)
{
    if (s_guard_task != NULL) return ESP_OK;
    if (s_lock == NULL) {
        s_lock = xSemaphoreCreateRecursiveMutex();
        if (s_lock == NULL) return ESP_ERR_NO_MEM;
    }
    return xTaskCreate(oled_guard_task, "oled_guard", 3072, NULL, 2, &s_guard_task) == pdPASS ? ESP_OK : ESP_FAIL;
}
