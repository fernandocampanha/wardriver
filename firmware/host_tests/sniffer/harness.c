#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "freertos/queue.h"

static int64_t g_now_us = 5000LL * 1000000;
int64_t esp_timer_get_time(void) { return g_now_us; }
void vTaskDelay(uint32_t t) { g_now_us += (int64_t)t * 1000; }
void vTaskDelete(TaskHandle_t t) { (void)t; }
int xTaskCreate(void (*fn)(void *), const char *n, uint32_t st, void *a, int p, TaskHandle_t *h) { (void)fn; (void)n; (void)st; (void)a; (void)p; if (h) *h = (void *)1; return pdPASS; }
SemaphoreHandle_t xSemaphoreCreateMutex(void) { return (void *)1; }
int xSemaphoreTake(SemaphoreHandle_t m, uint32_t t) { (void)m; (void)t; return 1; }
int xSemaphoreGive(SemaphoreHandle_t m) { (void)m; return 1; }
void harness_log(char lvl, const char *fmt, ...) { (void)lvl; (void)fmt; }

struct FakeQueue { uint32_t len, isz, head, count; uint8_t *data; };
QueueHandle_t xQueueCreate(uint32_t len, uint32_t isz) { QueueHandle_t q = calloc(1, sizeof *q); q->len = len; q->isz = isz; q->data = calloc(len, isz); return q; }
int xQueueSend(QueueHandle_t q, const void *item, uint32_t t) { (void)t; if (q->count == q->len) return 0; memcpy(q->data + ((q->head + q->count) % q->len) * q->isz, item, q->isz); q->count++; return 1; }
int xQueueReceive(QueueHandle_t q, void *item, uint32_t t) { (void)t; if (!q->count) return 0; memcpy(item, q->data + q->head * q->isz, q->isz); q->head = (q->head + 1) % q->len; q->count--; return 1; }

static void (*g_cb)(void *, wifi_promiscuous_pkt_type_t);
static int g_last_channel = 0;
esp_err_t esp_wifi_init(const wifi_init_config_t *c) { (void)c; return ESP_OK; }
esp_err_t esp_wifi_set_storage(wifi_storage_t s) { (void)s; return ESP_OK; }
esp_err_t esp_wifi_set_mode(wifi_mode_t m) { (void)m; return ESP_OK; }
esp_err_t esp_wifi_start(void) { return ESP_OK; }
esp_err_t esp_wifi_set_country(const wifi_country_t *c) { (void)c; return ESP_OK; }
esp_err_t esp_wifi_set_promiscuous_filter(const wifi_promiscuous_filter_t *f) { (void)f; return ESP_OK; }
esp_err_t esp_wifi_set_promiscuous_rx_cb(void (*cb)(void *, wifi_promiscuous_pkt_type_t)) { g_cb = cb; return ESP_OK; }
esp_err_t esp_wifi_set_promiscuous(int en) { (void)en; return ESP_OK; }
esp_err_t esp_wifi_set_channel(uint8_t ch, wifi_second_chan_t s) { (void)s; if (ch < 1 || ch > 13) return ESP_FAIL; g_last_channel = ch; return ESP_OK; }

#include "wifi_sniffer.c"

static const uint8_t AP1[6] = {0x70, 0xAA, 0xBB, 0x01, 0x02, 0x03};
static const uint8_t AP2[6] = {0x70, 0xAA, 0xBB, 0x0A, 0x0B, 0x0C};
static const uint8_t CLIENT[6] = {0xDE, 0xAD, 0xBE, 0xEF, 0x13, 0x37};
static const uint8_t PREV_AP[6] = {0xCA, 0xFE, 0xBA, 0xBE, 0x99, 0x88};
static const uint8_t BCAST[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

static int put_ssid(uint8_t *p, const char *ssid, bool zero_fill) {
    if (ssid == NULL) { p[0] = 0; p[1] = 0; return 2; }
    size_t n = strlen(ssid); p[0] = 0; p[1] = (uint8_t)n;
    if (zero_fill) memset(&p[2], 0, n); else memcpy(&p[2], ssid, n);
    return 2 + (int)n;
}
static int build_beacon(uint8_t *b, const uint8_t *bssid, const char *ssid, uint8_t ch, bool probe_resp) {
    memset(b, 0, 128); b[0] = probe_resp ? 0x50 : 0x80;
    memcpy(&b[4], probe_resp ? CLIENT : BCAST, 6);
    memcpy(&b[10], bssid, 6); memcpy(&b[16], bssid, 6);
    b[34] = 0x10; int o = 36; o += put_ssid(&b[o], ssid, false);
    b[o++] = 3; b[o++] = 1; b[o++] = ch;
    return o;
}
static int build_assoc(uint8_t *b, bool reassoc, const uint8_t *a1, const uint8_t *a3, const char *ssid, bool zero_fill) {
    memset(b, 0, 160); b[0] = reassoc ? 0x20 : 0x00;
    memcpy(&b[4], a1, 6); memcpy(&b[10], CLIENT, 6); memcpy(&b[16], a3, 6);
    int o = 24; b[o++] = 0x31; b[o++] = 0x04; b[o++] = 0x0A; b[o++] = 0x00;
    if (reassoc) { memcpy(&b[o], PREV_AP, 6); o += 6; }
    o += put_ssid(&b[o], ssid, zero_fill);
    b[o++] = 1; b[o++] = 2; b[o++] = 0x82; b[o++] = 0x84;
    return o;
}
static void inject(const uint8_t *frame, int len, uint8_t radio_ch, int8_t rssi, wifi_promiscuous_pkt_type_t type) {
    uint8_t mem[sizeof(wifi_promiscuous_pkt_t) + 256]; wifi_promiscuous_pkt_t *pkt = (void *)mem;
    pkt->rx_ctrl.rssi = rssi; pkt->rx_ctrl.channel = radio_ch; pkt->rx_ctrl.sig_len = (uint16_t)len;
    memcpy(pkt->payload, frame, len);
    g_cb(pkt, type);
}
static bool queue_pop(sniffer_raw_frame_t *f) { return xQueueReceive(s_frame_queue, f, 0) == 1; }
static void drain(void) { sniffer_raw_frame_t f; while (queue_pop(&f)) sniffer_process_frame(&f); }
static void send(const uint8_t *fr, int len, uint8_t ch, int8_t rssi) { inject(fr, len, ch, rssi, WIFI_PKT_MGMT); drain(); }

static struct { int calls; wifi_sniffer_network_t net; bool is_new, revealed; } g_cbinfo;
static void net_cb(const wifi_sniffer_network_t *n, bool is_new, bool revealed, void *ctx) { (void)ctx; g_cbinfo.calls++; g_cbinfo.net = *n; g_cbinfo.is_new = is_new; g_cbinfo.revealed = revealed; }

static void reset_world(void) {
    if (s_frame_queue) { sniffer_raw_frame_t f; while (queue_pop(&f)) {} }
    s_network_count = 0; s_seen_count = 0; if (s_seen_bloom) memset(s_seen_bloom, 0, SEEN_BLOOM_BYTES);
    memset(&s_stats, 0, sizeof s_stats); memset((void *)s_rx_by_channel, 0, sizeof s_rx_by_channel);
    memset(s_hints, 0, sizeof s_hints); s_hint_next = 0; memset(&g_cbinfo, 0, sizeof g_cbinfo);
    s_user_cb = net_cb; s_user_ctx = NULL; g_now_us += 100LL * 1000000;
}
static bool memory_contains(const void *hay, size_t hn, const uint8_t *needle, size_t nn) { const uint8_t *h = hay; for (size_t i = 0; i + nn <= hn; i++) if (!memcmp(h + i, needle, nn)) return true; return false; }
static wifi_sniffer_network_t *find(const uint8_t *bssid) { for (int i = 0; i < s_network_count; i++) if (!memcmp(s_networks[i].bssid, bssid, 6)) return &s_networks[i]; return NULL; }

static int fails = 0, checks = 0;
#define CHECK(c, m) do { checks++; if (!(c)) { printf("    FALHOU: %s\n", m); fails++; } else printf("    ok: %s\n", m); } while (0)

int main(void) {
    uint8_t fr[256]; int n;
    ESP_ERROR_CHECK(wifi_sniffer_init());
    ESP_ERROR_CHECK(wifi_sniffer_start(6, net_cb, NULL));

    printf("T1  beacon oculto (SSID de tamanho 0) e probe response com nome (comportamento que ja existia)\n");
    reset_world();
    n = build_beacon(fr, AP1, NULL, 6, false); send(fr, n, 6, -60);
    CHECK(find(AP1) && find(AP1)->ssid_hidden && s_stats.ssid_null_len == 1, "AP oculto entra na tabela como sem nome");
    n = build_beacon(fr, AP1, "Achada", 6, true); send(fr, n, 6, -61);
    CHECK(g_cbinfo.revealed && !strcmp(find(AP1)->ssid, "Achada") && s_stats.names_revealed_probe == 1, "probe response revela o nome (avisa o consumidor)");

    printf("T2  association request revela o nome de um AP oculto que ja esta na tabela\n");
    reset_world();
    n = build_beacon(fr, AP1, NULL, 6, false); send(fr, n, 6, -55);
    uint32_t seen0 = find(AP1)->times_seen; int8_t rssi0 = find(AP1)->rssi; g_cbinfo.calls = 0;
    n = build_assoc(fr, false, AP1, AP1, "RedeEscondida", false); send(fr, n, 6, -20);
    CHECK(!strcmp(find(AP1)->ssid, "RedeEscondida") && !find(AP1)->ssid_hidden, "nome descoberto");
    CHECK(g_cbinfo.calls == 1 && g_cbinfo.revealed && !g_cbinfo.is_new, "consumidor avisado como revelacao (fura o throttle)");
    CHECK(s_stats.names_revealed_assoc == 1 && s_stats.assoc_requests == 1, "contadores");
    CHECK(find(AP1)->rssi == rssi0 && g_cbinfo.net.rssi == rssi0, "RSSI do AP NAO foi trocado pelo do cliente (-20)");
    CHECK(find(AP1)->times_seen == seen0, "o pedido nao conta como 'AP visto de novo'");

    printf("T3  PRIVACIDADE: o MAC do cliente nunca sai da callback do radio\n");
    reset_world();
    n = build_assoc(fr, false, AP1, AP1, "Qualquer", false); inject(fr, n, 6, -40, WIFI_PKT_MGMT);
    sniffer_raw_frame_t q; CHECK(queue_pop(&q) && q.kind == SNIFFER_FRAME_ASSOC, "association request enfileirado");
    static const uint8_t ZERO6[6] = {0};
    CHECK(!memcmp(&q.payload[10], ZERO6, 6), "addr2 (cliente) zerado no quadro da fila");
    CHECK(!memory_contains(q.payload, sizeof q.payload, CLIENT, 6), "MAC do cliente nao aparece em nenhum byte do quadro enfileirado");
    n = build_assoc(fr, true, AP1, AP1, "Qualquer", false); inject(fr, n, 6, -40, WIFI_PKT_MGMT);
    CHECK(queue_pop(&q) && q.kind == SNIFFER_FRAME_REASSOC, "reassociation request enfileirado");
    CHECK(!memcmp(&q.payload[28], ZERO6, 6), "'AP anterior' do reassociation zerado");
    CHECK(!memory_contains(q.payload, sizeof q.payload, CLIENT, 6) && !memory_contains(q.payload, sizeof q.payload, PREV_AP, 6), "nem o cliente nem o AP anterior aparecem no quadro");
    reset_world();
    n = build_beacon(fr, AP1, NULL, 6, false); send(fr, n, 6, -50);
    n = build_assoc(fr, false, AP1, AP1, "Nome1", false); send(fr, n, 6, -30);
    n = build_assoc(fr, true, AP2, AP2, "Nome2", false); send(fr, n, 6, -30);
    CHECK(!memory_contains(s_networks, sizeof(wifi_sniffer_network_t) * s_network_capacity, CLIENT, 6), "MAC do cliente nao esta na tabela de redes");
    CHECK(!memory_contains(s_hints, sizeof s_hints, CLIENT, 6) && !memory_contains(s_hints, sizeof s_hints, PREV_AP, 6), "nem nas dicas");
    CHECK(!memory_contains(&g_cbinfo, sizeof g_cbinfo, CLIENT, 6), "nem no que e entregue ao consumidor (que grava e transmite)");

    printf("T4  association request de AP AINDA fora da tabela vira dica e e aplicada quando o beacon chegar\n");
    reset_world();
    n = build_assoc(fr, false, AP1, AP1, "DicaPrimeiro", false); send(fr, n, 6, -30);
    CHECK(find(AP1) == NULL, "nao cria rede so com o pedido (nao tem RSSI/cripto do AP)");
    n = build_beacon(fr, AP1, NULL, 6, false); send(fr, n, 6, -58);
    CHECK(find(AP1) && !strcmp(find(AP1)->ssid, "DicaPrimeiro") && g_cbinfo.is_new, "beacon oculto cria a rede JA com o nome da dica");
    CHECK(s_stats.names_revealed_assoc == 1, "contado como descoberta por association request");
    n = build_beacon(fr, AP2, NULL, 6, false); send(fr, n, 6, -58);
    CHECK(find(AP2)->ssid_hidden, "dica e por BSSID: outro AP continua oculto");

    printf("T5  dica velha (>30 s) nao vale\n");
    reset_world();
    n = build_assoc(fr, false, AP1, AP1, "Velha", false); send(fr, n, 6, -30);
    g_now_us += 31LL * 1000000;
    n = build_beacon(fr, AP1, NULL, 6, false); send(fr, n, 6, -58);
    CHECK(find(AP1)->ssid_hidden, "AP continua oculto");

    printf("T6  o que NAO pode virar nome\n");
    reset_world();
    n = build_beacon(fr, AP1, NULL, 6, false); send(fr, n, 6, -55);
    n = build_assoc(fr, false, AP1, AP2, "AddrDiferente", false); send(fr, n, 6, -30);
    CHECK(find(AP1)->ssid_hidden && s_stats.assoc_requests == 0, "addr1 != addr3 (nao e um pedido a um AP): ignorado");
    static const uint8_t MC[6] = {0x01, 0x00, 0x5E, 0x00, 0x00, 0x01};
    n = build_assoc(fr, false, MC, MC, "Multicast", false); send(fr, n, 6, -30);
    CHECK(s_stats.assoc_requests == 0, "BSSID multicast: ignorado");
    n = build_assoc(fr, false, AP1, AP1, NULL, false); send(fr, n, 6, -30);
    CHECK(find(AP1)->ssid_hidden, "SSID de tamanho 0: sem nome");
    n = build_assoc(fr, false, AP1, AP1, "ZZZZZZZZ", true); send(fr, n, 6, -30);
    CHECK(find(AP1)->ssid_hidden, "SSID so de zeros: sem nome");
    CHECK(s_stats.names_revealed_assoc == 0 && s_stats.assoc_requests == 2, "so os 2 pedidos validos (sem nome util) foram contados");

    printf("T7  AP que ja tem nome: o beacon manda, o association request nao sobrescreve\n");
    reset_world();
    n = build_beacon(fr, AP1, "NomeDoBeacon", 6, false); send(fr, n, 6, -50); g_cbinfo.calls = 0;
    n = build_assoc(fr, false, AP1, AP1, "OutroSSIDdoMesmoAP", false); send(fr, n, 6, -30);
    CHECK(!strcmp(find(AP1)->ssid, "NomeDoBeacon") && g_cbinfo.calls == 0 && s_stats.names_revealed_assoc == 0, "nome do beacon preservado, nenhum evento");

    printf("T8  reassociation: IEs comecam depois do 'AP atual' (offset 34)\n");
    reset_world();
    n = build_beacon(fr, AP1, NULL, 6, false); send(fr, n, 6, -55);
    n = build_assoc(fr, true, AP1, AP1, "ViaReassoc", false); send(fr, n, 6, -30);
    CHECK(!strcmp(find(AP1)->ssid, "ViaReassoc"), "nome lido corretamente de um reassociation request");

    printf("T9  so beacon, probe response e (re)association request passam pelo filtro\n");
    reset_world(); sniffer_raw_frame_t junk;
    const uint8_t others[] = {0x40, 0xB0, 0xC0, 0xA0, 0x10, 0x30, 0x60, 0xD0, 0x08, 0x48};
    for (size_t i = 0; i < sizeof others; i++) { memset(fr, 0, 128); fr[0] = others[i]; memcpy(&fr[10], CLIENT, 6); inject(fr, 60, 6, -40, WIFI_PKT_MGMT); }
    CHECK(!queue_pop(&junk), "probe request, auth, deauth, disassoc, respostas de assoc, action e dados: NENHUM enfileirado");
    n = build_beacon(fr, AP1, "X", 6, false); inject(fr, n, 6, -40, WIFI_PKT_DATA);
    CHECK(!queue_pop(&junk), "pacote que nao e de gerenciamento: descartado");
    n = build_assoc(fr, false, AP1, AP1, "X", false); inject(fr, 20, 6, -40, WIFI_PKT_MGMT);
    CHECK(!queue_pop(&junk), "association request curto demais: descartado");

    printf("T10 contadores por canal do radio\n");
    reset_world();
    for (int ch = 1; ch <= 13; ch++) { n = build_beacon(fr, AP1, "A", (uint8_t)ch, false); for (int k = 0; k < ch; k++) inject(fr, n, (uint8_t)ch, -60, WIFI_PKT_MGMT); }
    wifi_sniffer_stats_t st; wifi_sniffer_get_stats(&st); bool okc = true; for (int ch = 1; ch <= 13; ch++) if (st.rx_by_channel[ch] != (uint32_t)ch) okc = false;
    CHECK(okc, "canal N contou N quadros, do 1 ao 13");
    n = build_beacon(fr, AP1, "A", 6, false); inject(fr, n, 15, -60, WIFI_PKT_MGMT); inject(fr, n, 200, -60, WIFI_PKT_MGMT);
    CHECK(true, "canal fora da faixa (15, 200) nao estoura o vetor (AddressSanitizer)");

    printf("T11 channel hopping: cronograma e volta\n");
    CHECK(hop_dwell_ms(1) == 200 && hop_dwell_ms(6) == 200 && hop_dwell_ms(11) == 200, "1, 6 e 11 ficam 200 ms");
    CHECK(hop_dwell_ms(2) == 110 && hop_dwell_ms(13) == 110, "os demais ficam 110 ms (>= 1 beacon de 102 ms)");
    CHECK(wifi_sniffer_hop_sweep_ms() == 3 * 200 + 10 * 110, "volta completa pelos 13 canais = 1700 ms");
    bool seq_ok = true; uint8_t c = 1; int seen_mask = 0;
    for (int i = 0; i < 26; i++) { seen_mask |= 1 << c; if (i % 13 != 0 && c == 1) seq_ok = false; c = hop_next_channel(c); }
    CHECK(seq_ok && seen_mask == 0x3FFE, "percorre exatamente 1..13 e volta ao 1");
    CHECK(hop_next_channel(13) == 1 && hop_next_channel(0) == 1 && hop_next_channel(14) == 1, "13 -> 1; canal invalido volta ao primeiro");
    CHECK(g_last_channel == CONFIG_WARDRIVER_HOP_FIRST_CHANNEL, "start() sintoniza o primeiro canal e sobe a task de hopping");
    CHECK(esp_wifi_set_channel(13, 0) == ESP_OK && esp_wifi_set_channel(14, 0) != ESP_OK, "radio falso: 13 aceito, 14 recusado (como fora do Brasil)");

    printf("T12 dicas: anel de 16, a mais antiga e sobrescrita\n");
    reset_world();
    for (int i = 0; i < 20; i++) { uint8_t b[6] = {0x70, 0xAA, 0xBB, 0, 0, (uint8_t)(i + 1)}; n = build_assoc(fr, false, b, b, "Dica", false); send(fr, n, 6, -30); }
    int have = 0; for (int i = 0; i < NAME_HINT_SLOTS; i++) if (s_hints[i].at_us > 0) have++;
    uint8_t first[6] = {0x70, 0xAA, 0xBB, 0, 0, 1}, last[6] = {0x70, 0xAA, 0xBB, 0, 0, 20}; char sbuf[33]; uint8_t sl;
    CHECK(have == NAME_HINT_SLOTS, "16 dicas guardadas");
    CHECK(!hint_take_locked(first, g_now_us, sbuf, &sl) && hint_take_locked(last, g_now_us, sbuf, &sl), "a mais antiga foi descartada, a mais nova esta la");

    printf("\n%s: %d verificacoes, %d falha(s)\n", fails ? "REPROVADO" : "APROVADO", checks, fails);
    return fails ? 1 : 0;
}
