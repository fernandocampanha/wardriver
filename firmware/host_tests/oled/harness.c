#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "esp_timer.h"
#include "esp_rom_sys.h"
#include "driver/gpio.h"
#include "driver/i2c.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"

static int64_t g_now_us = 1000LL * 1000000;
int64_t esp_timer_get_time(void) { return g_now_us; }
void esp_rom_delay_us(uint32_t us) { g_now_us += us; }
void vTaskDelay(uint32_t ticks) { g_now_us += (int64_t)ticks * 1000; }
int xTaskCreate(void (*fn)(void *), const char *n, uint32_t st, void *a, int p, TaskHandle_t *h) { (void)fn; (void)n; (void)st; (void)a; (void)p; *h = (void *)1; return pdPASS; }
static int g_lock_depth = 0;
SemaphoreHandle_t xSemaphoreCreateRecursiveMutex(void) { return (void *)1; }
int xSemaphoreTakeRecursive(SemaphoreHandle_t m, uint32_t t) { (void)m; (void)t; g_lock_depth++; return 1; }
int xSemaphoreGiveRecursive(SemaphoreHandle_t m) { (void)m; g_lock_depth--; return 1; }

static char g_log[64][200]; static int g_log_n = 0; static bool g_log_print = false;
void harness_log(char lvl, const char *fmt, ...) {
    char buf[200]; va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof buf, fmt, ap); va_end(ap);
    if (g_log_n < 64) snprintf(g_log[g_log_n++], sizeof g_log[0], "[%c] %s", lvl, buf);
    if (g_log_print) printf("      [%c] %s\n", lvl, buf);
}
static bool log_has(const char *needle) { for (int i = 0; i < g_log_n; i++) if (strstr(g_log[i], needle)) return true; return false; }

typedef struct {
    bool display_on, pump_on; int addr_mode;
    int col_start, col_end, page_start, page_end, col, page;
    uint8_t gddram[8][128];
    int pending_cmd, pending_need, pending_have; uint8_t pending_params[2];
    int fail_next; bool fail_all; bool bus_stuck; int scl_pulses; bool scl_stuck_low;
    bool driver_installed;
    int off_events, pump_off_events, transactions;
} ssd_t;
static ssd_t ssd;
static int g_pin_level[64];

static void ssd_power_glitch(void) {
    ssd.display_on = false; ssd.pump_on = false; ssd.addr_mode = 2;
    ssd.col_start = 0; ssd.col_end = 127; ssd.page_start = 0; ssd.page_end = 7; ssd.col = 0; ssd.page = 0;
    ssd.pending_need = 0; ssd.pending_have = 0;
    memset(ssd.gddram, 0, sizeof ssd.gddram);
}
static void ssd_apply(void) {
    uint8_t p0 = ssd.pending_params[0], p1 = ssd.pending_params[1];
    switch (ssd.pending_cmd) {
        case 0x8D: if (p0 == 0x14) ssd.pump_on = true; else if (p0 == 0x10) { if (ssd.pump_on) ssd.pump_off_events++; ssd.pump_on = false; } break;
        case 0x20: ssd.addr_mode = p0 & 3; break;
        case 0x21: ssd.col_start = p0; ssd.col_end = p1; ssd.col = p0; break;
        case 0x22: ssd.page_start = p0; ssd.page_end = p1; ssd.page = p0; break;
        default: break;
    }
}
static void ssd_cmd(uint8_t b) {
    if (ssd.pending_need > 0) {
        ssd.pending_params[ssd.pending_have++] = b;
        if (ssd.pending_have == ssd.pending_need) { ssd_apply(); ssd.pending_need = 0; }
        return;
    }
    switch (b) {
        case 0xAE: if (ssd.display_on) ssd.off_events++; ssd.display_on = false; break;
        case 0xAF: ssd.display_on = true; break;
        case 0x8D: case 0x20: case 0xD5: case 0xA8: case 0xD3: case 0xDA: case 0x81: case 0xD9: case 0xDB:
            ssd.pending_cmd = b; ssd.pending_need = 1; ssd.pending_have = 0; break;
        case 0x21: case 0x22: ssd.pending_cmd = b; ssd.pending_need = 2; ssd.pending_have = 0; break;
        case 0xE3: break;
        default:
            if (b >= 0xB0 && b <= 0xB7) { if (ssd.addr_mode == 2) ssd.page = b & 7; }
            else if (b <= 0x0F) { if (ssd.addr_mode == 2) ssd.col = (ssd.col & 0xF0) | b; }
            else if (b >= 0x10 && b <= 0x1F) { if (ssd.addr_mode == 2) ssd.col = (ssd.col & 0x0F) | ((b & 0x0F) << 4); }
            break;
    }
}
static void ssd_data(uint8_t b) {
    ssd.gddram[ssd.page & 7][ssd.col & 127] = b;
    if (ssd.addr_mode == 0) {
        if (++ssd.col > ssd.col_end) { ssd.col = ssd.col_start; if (++ssd.page > ssd.page_end) ssd.page = ssd.page_start; }
    } else {
        ssd.col = (ssd.col + 1) & 127;
    }
}
esp_err_t i2c_param_config(int port, const i2c_config_t *c) { (void)port; (void)c; return ESP_OK; }
esp_err_t i2c_driver_install(int port, i2c_mode_t m, size_t a, size_t b, int c) { (void)port; (void)m; (void)a; (void)b; (void)c;
    if (ssd.driver_installed) return ESP_ERR_INVALID_STATE;
    ssd.driver_installed = true; return ESP_OK; }
esp_err_t i2c_driver_delete(int port) { (void)port; if (!ssd.driver_installed) return ESP_ERR_INVALID_STATE; ssd.driver_installed = false; return ESP_OK; }
esp_err_t i2c_master_write_to_device(int port, uint8_t addr, const uint8_t *buf, size_t len, uint32_t ticks) {
    (void)port; (void)ticks;
    g_now_us += 100 + (int64_t)len * 25;
    ssd.transactions++;
    if (!ssd.driver_installed) return ESP_ERR_INVALID_STATE;
    if (ssd.bus_stuck) { g_now_us += 100000; return ESP_ERR_TIMEOUT; }
    if (ssd.fail_all) return ESP_FAIL;
    if (ssd.fail_next > 0) { ssd.fail_next--; return ESP_FAIL; }
    if (addr != 0x3C) return ESP_FAIL;
    if (len >= 1 && buf[0] == 0x00) for (size_t i = 1; i < len; i++) ssd_cmd(buf[i]);
    else if (len >= 1 && buf[0] == 0x40) for (size_t i = 1; i < len; i++) ssd_data(buf[i]);
    return ESP_OK;
}
int gpio_config(const gpio_config_t *c) { (void)c; return 0; }
int gpio_set_level(int pin, uint32_t level) {
    if (pin == 22 && g_pin_level[22] == 1 && level == 0 && ssd.bus_stuck) {
        if (++ssd.scl_pulses >= 9) ssd.bus_stuck = false;
    }
    g_pin_level[pin] = (int)level; return 0;
}
int gpio_get_level(int pin) {
    if (pin == 23 && ssd.bus_stuck) return 0;
    if (pin == 22 && ssd.scl_stuck_low) return 0;
    return g_pin_level[pin];
}

#include "oled_display.c"

static void reset_world(void) {
    memset(&ssd, 0, sizeof ssd); memset(g_pin_level, 1, sizeof g_pin_level);
    ssd_power_glitch(); ssd.addr_mode = 2;
    s_lock = NULL; s_driver_installed = false; s_initialized = false; s_heartbeat_on = false; s_guard_task = NULL;
    s_consecutive_failures = 0; s_last_ok_us = s_last_full_us = s_next_init_try_us = 0;
    memset(&s_stats, 0, sizeof s_stats); memset(s_framebuf, 0, sizeof s_framebuf);
    g_log_n = 0; g_lock_depth = 0;
}
static bool visible(void) { return ssd.display_on && ssd.pump_on && memcmp(ssd.gddram, s_framebuf, sizeof s_framebuf) == 0; }
static bool page_has_pixels(int page) { for (int i = 0; i < 128; i++) if (ssd.gddram[page][i]) return true; return false; }
static void advance_ms(int ms) { g_now_us += (int64_t)ms * 1000; }

static int fails = 0, checks = 0;
#define CHECK(cond, msg) do { checks++; if (!(cond)) { printf("    FALHOU: %s\n", msg); fails++; } else printf("    ok: %s\n", msg); } while (0)

static void healthy_ui(void) { oled_display_show_status(128, 3, OLED_GPS_PHONE, true, 12); }

int main(void) {
    printf("S1  boot normal\n");
    reset_world(); CHECK(oled_display_init() == ESP_OK, "init OK"); healthy_ui();
    CHECK(visible(), "painel ligado, com charge pump, mostrando o quadro"); CHECK(page_has_pixels(2), "linha REDES desenhada");

    printf("S2  painel RESETA por queda de tensao mas continua dando ACK (o caso do powerbank)\n");
    reset_world(); oled_display_init(); healthy_ui(); oled_display_start_guard();
    ssd_power_glitch();
    CHECK(!visible(), "logo apos a queda: tela apagada (reproduz o defeito)");
    uint32_t fail_before = s_stats.pushes_failed;
    advance_ms(1000); oled_guard_tick();
    CHECK(visible(), "no proximo ciclo do guardiao (<=1 s) a tela reacende");
    CHECK(page_has_pixels(2), "com o MESMO conteudo (contagem preservada, nao voltou em branco)");
    CHECK(s_stats.pushes_failed == fail_before && s_stats.bus_recoveries == 0, "sem nenhum erro de I2C: a recuperacao por erro nunca teria disparado");

    printf("S3  laco principal PARADO (flush do SPIFFS, poda) enquanto o painel reseta\n");
    reset_world(); oled_display_init(); healthy_ui(); oled_display_start_guard();
    bool ok3 = true;
    for (int t = 0; t < 12; t++) { advance_ms(1000); if (t == 3 || t == 8) ssd_power_glitch(); oled_guard_tick(); if (!visible()) ok3 = false; }
    CHECK(ok3, "sem o main chamar update() por 12 s, o guardiao mantem a tela ligada apos 2 quedas");
    CHECK(s_stats.guard_pushes >= 10, "o guardiao foi quem refez os quadros");

    printf("S4  painel deixado NO MEIO de um comando de 2 parametros (glitch no barramento)\n");
    reset_world(); oled_display_init(); healthy_ui();
    ssd.pending_cmd = 0x21; ssd.pending_need = 2; ssd.pending_have = 0; ssd.display_on = false; ssd.pump_on = false;
    CHECK(oled_display_update() == ESP_OK, "update OK"); CHECK(visible(), "tela ligada e quadro correto"); CHECK(ssd.pending_need == 0, "parser do painel ressincronizado");

    printf("S5  init falha no BOOT e o painel so aparece depois\n");
    reset_world(); ssd.fail_all = true;
    CHECK(oled_display_init() != ESP_OK, "init falha (sem ACK)"); oled_display_start_guard();
    int tx0 = ssd.transactions; CHECK(oled_display_update() == ESP_ERR_INVALID_STATE, "update() nao insiste (quem insiste e o guardiao)");
    CHECK(ssd.transactions == tx0, "e nao gasta o barramento");
    advance_ms(5200); ssd.fail_all = false; oled_guard_tick();
    CHECK(s_initialized && visible(), "o guardiao sobe o painel numa nova tentativa (antes: tela apagada ate reiniciar)"); CHECK(s_stats.init_retries >= 1, "contou a tentativa");

    printf("S6  init repetido e init parcialmente falho (driver ja instalado)\n");
    reset_world(); CHECK(oled_display_init() == ESP_OK && oled_display_init() == ESP_OK, "init duas vezes seguidas OK (antes: ESP_ERR_INVALID_STATE)");
    reset_world(); ssd.fail_next = 2; CHECK(oled_display_init() == ESP_OK && visible(), "1a tentativa falha no meio, a 2a reinstala o driver e conclui");

    printf("S7  barramento PRESO (SDA segurado pelo painel): recuperacao automatica\n");
    reset_world(); oled_display_init(); healthy_ui(); ssd.bus_stuck = true; ssd.scl_pulses = 0;
    esp_err_t r1 = oled_display_update(), r2 = oled_display_update(), r3 = oled_display_update();
    CHECK(r1 == ESP_ERR_TIMEOUT && r2 == ESP_ERR_TIMEOUT, "duas falhas: ainda so tenta de novo");
    CHECK(r3 == ESP_OK, "na 3a falha seguida faz bus clear + reinstala + init a frio");
    CHECK(ssd.scl_pulses >= 9 && !ssd.bus_stuck, "9 pulsos de clock liberaram o SDA"); CHECK(s_stats.bus_recoveries == 1, "1 recuperacao contada");
    CHECK(visible() && page_has_pixels(2), "tela de volta com o conteudo atual (nao em branco)");

    printf("S7b bus clear no BOOT (o painel nao reseta junto com o ESP32)\n");
    reset_world(); ssd.bus_stuck = true; ssd.scl_pulses = 0;
    CHECK(oled_display_init() == ESP_OK, "init consegue mesmo com SDA preso desde antes do reset do ESP32"); CHECK(visible(), "tela ligada");

    printf("S8  barramento MORTO por 2 minutos, depois volta\n");
    reset_world(); oled_display_init(); healthy_ui(); oled_display_start_guard(); ssd.fail_all = true;
    for (int t = 0; t < 120; t++) { advance_ms(1000); if (t % 2 == 0) healthy_ui(); oled_guard_tick(); }
    CHECK(s_stats.bus_recoveries == 1, "so 1 recuperacao por erro (nao martela)");
    CHECK(s_stats.init_retries >= 15 && s_stats.init_retries <= 30, "depois disso, ~1 tentativa a cada 5 s, so pelo guardiao");
    CHECK(g_lock_depth == 0, "mutex balanceado");
    ssd.fail_all = false; int back = -1; for (int t = 0; t < 8; t++) { advance_ms(1000); oled_guard_tick(); if (visible()) { back = t + 1; break; } }
    CHECK(back > 0 && back <= 6, "volta sozinho em ate ~5 s depois que o barramento volta");

    printf("S9  SCL preso em baixo (nao se resolve por software): nao trava e avisa\n");
    reset_world(); oled_display_init(); healthy_ui(); ssd.scl_stuck_low = true; ssd.fail_all = true;
    for (int i = 0; i < 4; i++) { advance_ms(1000); oled_display_update(); }
    CHECK(log_has("SCL continua em nivel baixo"), "diagnostico explicito no log"); CHECK(g_lock_depth == 0, "sem travar");

    printf("S10 reinicio a frio periodico (300 s) e ausencia de piscar nos re-armes normais\n");
    reset_world(); oled_display_init(); healthy_ui(); oled_display_start_guard(); int off0 = ssd.off_events, pump0 = ssd.pump_off_events;
    for (int t = 0; t < 320; t++) { advance_ms(1000); if (t % 2 == 0) healthy_ui(); oled_guard_tick(); }
    CHECK(s_stats.full_reinits == 1, "exatamente 1 reinicio a frio em 320 s");
    CHECK(ssd.off_events - off0 == 1 && ssd.pump_off_events - pump0 == 1, "so o reinicio a frio desliga display/charge pump; ~300 re-armes normais nao piscam");
    CHECK(visible(), "tela ligada no fim");

    printf("S11 pontinho (heartbeat) alterna a cada quadro\n");
    reset_world(); oled_display_init(); advance_ms(1000); healthy_ui(); uint8_t a = ssd.gddram[0][127]; advance_ms(1000); healthy_ui(); uint8_t b = ssd.gddram[0][127]; advance_ms(1000); healthy_ui(); uint8_t c = ssd.gddram[0][127];
    CHECK(a != b && b != c && a == c, "canto superior direito liga/desliga a cada segundo");
    healthy_ui(); uint8_t d1 = ssd.gddram[0][127]; healthy_ui(); uint8_t d2 = ssd.gddram[0][127];
    CHECK(d1 == d2, "dois quadros no mesmo segundo NAO se cancelam (segue o relogio, nao a contagem de quadros)");

    printf("S12 cadencia: guardiao 1/s + main a cada 2 s\n");
    reset_world(); oled_display_init(); healthy_ui(); oled_display_start_guard(); uint32_t p0 = s_stats.pushes_ok;
    for (int t = 0; t < 60; t++) { advance_ms(1000); if (t % 2 == 0) healthy_ui(); oled_guard_tick(); }
    uint32_t pushes = s_stats.pushes_ok - p0; printf("    quadros em 60 s: %u\n", (unsigned)pushes); CHECK(pushes >= 85 && pushes <= 95, "~1,5 quadro/s (60 do guardiao + 30 do main), poucos % do barramento");

    printf("S13 aleatorio: 40 quedas do painel em 10 min; pior tempo de tela apagada\n");
    reset_world(); oled_display_init(); healthy_ui(); oled_display_start_guard();
    uint32_t rng = 12345; int64_t dark_since = -1, worst_ms = 0; int glitches = 0;
    for (int step = 0; step < 6000; step++) {
        advance_ms(100);
        if ((rng = rng * 1103515245u + 12345u), ((rng >> 16) % 150) == 0) { ssd_power_glitch(); glitches++; }
        if (step % 20 == 5) healthy_ui();
        if (step % 10 == 9) oled_guard_tick();
        if (!visible()) { if (dark_since < 0) dark_since = g_now_us; }
        else if (dark_since >= 0) { int64_t d = (g_now_us - dark_since) / 1000; if (d > worst_ms) worst_ms = d; dark_since = -1; }
    }
    printf("    quedas simuladas: %d | pior tela apagada: %lld ms\n", glitches, (long long)worst_ms);
    CHECK(glitches >= 20, "houve quedas suficientes"); CHECK(worst_ms <= 1100, "a tela nunca fica apagada mais de ~1 s (100 ms de granularidade)"); CHECK(g_lock_depth == 0, "mutex balanceado");

    printf("\n%s: %d verificacoes, %d falha(s)\n", fails ? "REPROVADO" : "APROVADO", checks, fails);
    return fails ? 1 : 0;
}
