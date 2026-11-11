#include "bt_spp_sync.h"
#include "wardriver_record.h"
#include "phone_gps.h"

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <dirent.h>
#include <errno.h>
#include <sys/stat.h>
#include <unistd.h>
#include <inttypes.h>

#include "esp_log.h"
#include "esp_err.h"
#include "esp_bt.h"
#include "esp_bt_main.h"
#include "esp_gap_bt_api.h"
#include "esp_bt_device.h"
#include "esp_spp_api.h"
#include "esp_rom_crc.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "sdkconfig.h"

static const char *TAG = "bt_spp_sync";

static char s_storage_root[32] = "";
static char s_current_session_file[40] = "";
static char s_current_session_id[40] = "";

static volatile int s_active_fd = -1;
static SemaphoreHandle_t s_write_mutex = NULL;

static volatile bool s_suppress_stream = false;

static bool s_backlog_truncated = false;

#define MAX_PENDING_FILES 16
typedef struct {
    char filename[40];
    uint32_t crc32;
} pending_file_t;

static pending_file_t s_pending[MAX_PENDING_FILES];
static int s_pending_count = 0;

static bt_spp_sync_rotate_fn_t s_rotate_fn = NULL;
static char *s_current_session_buf = NULL;
static size_t s_current_session_buf_len = 0;

typedef void (*work_cb_t)(uint16_t event, void *param);

typedef struct {
    work_cb_t cb;
    uint16_t event;
    void *param;
} work_msg_t;

static QueueHandle_t s_work_queue = NULL;

static bool work_dispatch(work_cb_t cb, uint16_t event, void *param, size_t param_len)
{
    work_msg_t msg = { .cb = cb, .event = event, .param = NULL };
    if (param_len > 0 && param != NULL) {
        msg.param = malloc(param_len);
        if (msg.param == NULL) return false;
        memcpy(msg.param, param, param_len);
    }
    if (xQueueSend(s_work_queue, &msg, pdMS_TO_TICKS(10)) != pdTRUE) {
        ESP_LOGE(TAG, "fila de trabalho cheia, evento 0x%x descartado", event);
        if (msg.param) free(msg.param);
        return false;
    }
    return true;
}

static void work_task(void *arg)
{
    work_msg_t msg;
    for (;;) {
        if (xQueueReceive(s_work_queue, &msg, portMAX_DELAY) == pdTRUE) {
            if (msg.cb) msg.cb(msg.event, msg.param);
            if (msg.param) free(msg.param);
        }
    }
}

#define SEND_STALL_TIMEOUT_US_CONTROL  (5LL * 1000000)
#define SEND_STALL_TIMEOUT_US_LIVE     (200LL * 1000)

static volatile bool s_conn_broken = false;

static bool send_all(int fd, const char *buf, size_t len, int64_t stall_timeout_us)
{
    size_t sent = 0;
    int64_t last_progress_us = esp_timer_get_time();
    while (sent < len) {
        ssize_t n = write(fd, buf + sent, len - sent);
        if (n > 0) {
            sent += (size_t)n;
            last_progress_us = esp_timer_get_time();
            continue;
        }
        int err = errno;
        bool transient = (n == 0) || (err == EAGAIN) || (err == EWOULDBLOCK) || (err == EINTR) || (err == ENOMEM);
        if (transient && (esp_timer_get_time() - last_progress_us) < stall_timeout_us) {
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }
        ESP_LOGW(TAG, "write no SPP falhou apos %u/%u bytes (retorno=%d errno=%d: %s%s)",
                 (unsigned)sent, (unsigned)len, (int)n, err, strerror(err),
                 transient ? ", estagnado" : "");
        return false;
    }
    return true;
}

static bool send_line_locked(int fd, const char *buf, size_t len)
{
    if (xSemaphoreTake(s_write_mutex, pdMS_TO_TICKS(2000)) != pdTRUE) {
        ESP_LOGW(TAG, "mutex de escrita ocupado ha >2s, envio de %u bytes descartado", (unsigned)len);
        s_conn_broken = true;
        return false;
    }
    bool ok = send_all(fd, buf, len, SEND_STALL_TIMEOUT_US_CONTROL);
    xSemaphoreGive(s_write_mutex);
    if (!ok) s_conn_broken = true;
    return ok;
}

bool bt_spp_sync_is_connected(void)
{
    return s_active_fd >= 0;
}

void bt_spp_sync_stream_record(const void *data, size_t len)
{
    int fd = s_active_fd;
    if (fd < 0 || data == NULL || s_suppress_stream) return;

    if (xSemaphoreTake(s_write_mutex, pdMS_TO_TICKS(50)) != pdTRUE) {
        return;
    }

    uint8_t frame[1 + 128];
    if (len > sizeof(frame) - 1) {
        xSemaphoreGive(s_write_mutex);
        return;
    }
    frame[0] = WARDRIVER_LIVE_RECORD_MARKER;
    memcpy(&frame[1], data, len);
    bool ok = send_all(fd, (const char *)frame, 1 + len, SEND_STALL_TIMEOUT_US_LIVE);
    xSemaphoreGive(s_write_mutex);
    if (!ok) s_conn_broken = true;
}

void bt_spp_sync_send_status_line(const char *text)
{
    int fd = s_active_fd;
    if (fd < 0 || text == NULL || s_suppress_stream) return;

    static char line[800];
    int n = snprintf(line, sizeof(line), "%s\r\n", text);
    if (n <= 0) return;
    if ((size_t)n >= sizeof(line)) {
        n = sizeof(line) - 1;
        line[n - 2] = '\r';
        line[n - 1] = '\n';
    }

    if (xSemaphoreTake(s_write_mutex, pdMS_TO_TICKS(50)) != pdTRUE) return;
    bool ok = send_all(fd, line, (size_t)n, SEND_STALL_TIMEOUT_US_LIVE);
    xSemaphoreGive(s_write_mutex);
    if (!ok) s_conn_broken = true;
}

static void handle_loc_line(const char *line)
{
    double lat, lon;
    float accuracy_m;
    if (sscanf(line + 4, "%lf %lf %f", &lat, &lon, &accuracy_m) == 3) {
        phone_gps_update(lat, lon, accuracy_m);
        ESP_LOGI(TAG, "LOC recebida do celular: lat=%.6f lon=%.6f accuracy=%.1fm", lat, lon, accuracy_m);
    } else {
        ESP_LOGW(TAG, "linha LOC malformada, ignorada: %s", line);
    }
}

static bool remove_confirmed_file(const char *filename)
{
    char path[300];
    snprintf(path, sizeof(path), "%s/%s", s_storage_root, filename);
    if (remove(path) == 0) return true;
    if (errno == ENOENT) {
        ESP_LOGW(TAG, "%s ja nao existia no storage ao confirmar", filename);
        return true;
    }
    ESP_LOGE(TAG, "falha ao apagar %s (errno=%d: %s) — continua no storage e sera oferecido de novo na "
                  "proxima conexao", filename, errno, strerror(errno));
    s_backlog_truncated = false;
    return false;
}

static void handle_ack_line(int fd, const char *line)
{
    char reply[128];

    if (strcmp(line, "ACK ALL") == 0) {
        int removed = 0, failed = 0;
        for (int i = 0; i < s_pending_count; i++) {
            if (remove_confirmed_file(s_pending[i].filename)) removed++; else failed++;
        }
        ESP_LOGI(TAG, "ACK ALL recebido, %d arquivo(s) removido(s), %d falha(s)", removed, failed);
        if (failed == 0) {
            snprintf(reply, sizeof(reply), "OK ACK ALL (%d removido(s))\r\n", removed);
        } else {
            snprintf(reply, sizeof(reply), "ERRO ACK ALL: %d removido(s), %d NAO removido(s)\r\n", removed, failed);
        }
        send_line_locked(fd, reply, strlen(reply));
        s_pending_count = 0;
        return;
    }

    uint32_t crc;
    if (strncmp(line, "ACK ", 4) != 0 || sscanf(line + 4, "%" SCNx32, &crc) != 1) {
        snprintf(reply, sizeof(reply), "ERRO comando nao reconhecido: %s\r\n", line);
        send_line_locked(fd, reply, strlen(reply));
        return;
    }

    for (int i = 0; i < s_pending_count; i++) {
        if (s_pending[i].crc32 == crc) {
            bool ok = remove_confirmed_file(s_pending[i].filename);
            ESP_LOGI(TAG, "ACK %08" PRIx32 " -> %s removido=%d", crc, s_pending[i].filename, ok);
            if (ok) {
                snprintf(reply, sizeof(reply), "OK removido %s\r\n", s_pending[i].filename);
            } else {
                snprintf(reply, sizeof(reply), "ERRO nao foi possivel remover %s\r\n", s_pending[i].filename);
            }
            send_line_locked(fd, reply, strlen(reply));

            s_pending[i] = s_pending[--s_pending_count];
            return;
        }
    }

    snprintf(reply, sizeof(reply), "ERRO checksum desconhecido: %08" PRIx32 "\r\n", crc);
    send_line_locked(fd, reply, strlen(reply));
}

typedef enum {
    BACKLOG_OFFER_OK,
    BACKLOG_OFFER_SKIPPED,
    BACKLOG_OFFER_CONN_BROKEN,
} backlog_offer_result_t;

static bool file_fully_readable(FILE *f, long expected_size)
{
    char buf[256];
    size_t n;
    long total = 0;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) total += (long)n;
    bool ok = !ferror(f) && (expected_size < 0 || total == expected_size);
    rewind(f);
    clearerr(f);
    return ok;
}

static backlog_offer_result_t offer_file_for_backlog(int fd, const char *filename)
{
    char path[300];
    char line[320];
    snprintf(path, sizeof(path), "%s/%s", s_storage_root, filename);

    FILE *f = fopen(path, "r");
    if (f == NULL) {
        ESP_LOGW(TAG, "backlog: nao foi possivel abrir %s (errno=%d: %s)", filename, errno, strerror(errno));
        snprintf(line, sizeof(line), "--- falha ao abrir %s ---\r\n", filename);
        return send_line_locked(fd, line, strlen(line)) ? BACKLOG_OFFER_OK : BACKLOG_OFFER_CONN_BROKEN;
    }

    struct stat st;
    long file_size = (fstat(fileno(f), &st) == 0) ? (long)st.st_size : -1;

    if (!file_fully_readable(f, file_size)) {
        fclose(f);
        ESP_LOGE(TAG, "backlog: %s permanentemente corrompido (fstat=%ld bytes, leitura incompleta) — apagando",
                 filename, file_size);
        if (remove(path) != 0) {
            ESP_LOGW(TAG, "backlog: nao foi possivel apagar %s (errno=%d: %s)", filename, errno, strerror(errno));
        }
        return BACKLOG_OFFER_SKIPPED;
    }

    ESP_LOGI(TAG, "backlog: oferecendo %s (%ld bytes)", filename, file_size);
    snprintf(line, sizeof(line), "--- BEGIN %s bytes=%ld ---\r\n", filename, file_size);
    if (!send_line_locked(fd, line, strlen(line))) {
        fclose(f);
        ESP_LOGW(TAG, "backlog: falha ao enviar o BEGIN de %s — conexao sera reiniciada", filename);
        return BACKLOG_OFFER_CONN_BROKEN;
    }

    uint32_t crc = 0;
    long total = 0;
    char filebuf[256];
    size_t n;
    while ((n = fread(filebuf, 1, sizeof(filebuf), f)) > 0) {
        if (!send_line_locked(fd, filebuf, n)) {

            fclose(f);
            ESP_LOGW(TAG, "backlog: envio de %s interrompido apos %ld/%ld bytes — conexao sera reiniciada",
                     filename, total, file_size);
            return BACKLOG_OFFER_CONN_BROKEN;
        }
        crc = esp_rom_crc32_le(crc, (const uint8_t *)filebuf, n);
        total += (long)n;
    }
    bool read_err = ferror(f);
    fclose(f);
    if (read_err || (file_size >= 0 && total != file_size)) {

        ESP_LOGE(TAG, "backlog: leitura de %s inconsistente (%ld lidos, %ld anunciados, erro=%d) — conexao sera reiniciada",
                 filename, total, file_size, read_err);
        s_conn_broken = true;
        return BACKLOG_OFFER_CONN_BROKEN;
    }

    snprintf(line, sizeof(line), "\r\n--- CHECKSUM %s crc32=%08" PRIx32 " bytes=%ld ---\r\n",
             filename, crc, total);
    if (!send_line_locked(fd, line, strlen(line))) {
        ESP_LOGW(TAG, "backlog: falha ao enviar o CHECKSUM de %s — conexao sera reiniciada", filename);
        return BACKLOG_OFFER_CONN_BROKEN;
    }
    ESP_LOGI(TAG, "backlog: %s enviado (%ld bytes, crc32=%08" PRIx32 "), aguardando ACK", filename, total, crc);

    strncpy(s_pending[s_pending_count].filename, filename,
            sizeof(s_pending[s_pending_count].filename) - 1);
    s_pending[s_pending_count].filename[sizeof(s_pending[s_pending_count].filename) - 1] = '\0';
    s_pending[s_pending_count].crc32 = crc;
    s_pending_count++;
    return BACKLOG_OFFER_OK;
}

static void offer_backlog_batch(int fd)
{
    s_backlog_truncated = false;
    DIR *dir = (s_storage_root[0] != '\0') ? opendir(s_storage_root) : NULL;
    if (dir == NULL) return;

    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL && s_pending_count < MAX_PENDING_FILES) {
        size_t name_len = strlen(entry->d_name);
        if (name_len < 5 || strcmp(entry->d_name + name_len - 4, ".wdr") != 0) {
            continue;
        }
        if (s_current_session_file[0] != '\0' && strcmp(entry->d_name, s_current_session_file) == 0) {
            continue;
        }

        backlog_offer_result_t result = offer_file_for_backlog(fd, entry->d_name);
        if (result == BACKLOG_OFFER_CONN_BROKEN) {
            break;
        }

    }
    s_backlog_truncated = !s_conn_broken && (s_pending_count >= MAX_PENDING_FILES);
    closedir(dir);
}

static void do_manual_sync(int fd)
{
    if (s_rotate_fn == NULL) {
        const char *msg = "ERRO sincronizacao sem backend de storage configurado\r\n";
        send_line_locked(fd, msg, strlen(msg));
        return;
    }
    if (s_pending_count >= MAX_PENDING_FILES) {
        const char *msg = "ERRO backlog cheio, confirme os ACKs pendentes antes de sincronizar de novo\r\n";
        send_line_locked(fd, msg, strlen(msg));
        return;
    }

    char old_id[40], new_id[40];
    if (s_rotate_fn(old_id, sizeof(old_id), new_id, sizeof(new_id)) == ESP_OK) {
        ESP_LOGI(TAG, "sincronizacao manual: rotacionando sessao ativa (%s -> %s)", old_id, new_id);

        if (s_current_session_buf != NULL) {
            strncpy(s_current_session_buf, new_id, s_current_session_buf_len - 1);
            s_current_session_buf[s_current_session_buf_len - 1] = '\0';
        }
        bt_spp_sync_set_current_session(new_id);

        char session_line[64];
        snprintf(session_line, sizeof(session_line), "--- SESSION %s ---\r\n", new_id);
        send_line_locked(fd, session_line, strlen(session_line));

        s_suppress_stream = true;
        if (old_id[0] != '\0') {
            char old_filename[48];
            snprintf(old_filename, sizeof(old_filename), "%s.wdr", old_id);
            (void)offer_file_for_backlog(fd, old_filename);
        }
        if (!s_conn_broken) {
            offer_backlog_batch(fd);
        }
        s_suppress_stream = false;
    } else {
        ESP_LOGW(TAG, "sincronizacao manual: falha ao rotacionar sessao ativa");
        const char *msg = "ERRO falha ao rotacionar sessao\r\n";
        send_line_locked(fd, msg, strlen(msg));
    }
}

static void spp_connection_task(void *arg)
{
    int fd = (int)(intptr_t)arg;
    char line[320];

    s_conn_broken = false;
    snprintf(line, sizeof(line), "=== WARDRIVER — sincronizando %s ===\r\n\r\n",
             s_storage_root[0] != '\0' ? s_storage_root : "(nenhum storage ativo)");
    send_line_locked(fd, line, strlen(line));

    s_pending_count = 0;
    if (!s_conn_broken) offer_backlog_batch(fd);
    if (s_conn_broken) goto connection_broken;

    snprintf(line, sizeof(line),
             "=== FIM DO BACKLOG (%d sessao(oes) pendente(s) de confirmacao) ===\r\n"
             "Envie \"ACK <crc32_hex>\" por arquivo confirmado, ou \"ACK ALL\".\r\n"
             "Streaming ao vivo da sessao atual comeca agora.\r\n",
             s_pending_count);
    send_line_locked(fd, line, strlen(line));

    snprintf(line, sizeof(line), "--- SESSION %s ---\r\n", s_current_session_id);
    send_line_locked(fd, line, strlen(line));

    if (s_conn_broken) goto connection_broken;

    s_active_fd = fd;

    char rxbuf[256];
    char rxline[160];
    size_t rxline_len = 0;
    for (;;) {
        if (s_conn_broken) break;
        int n = read(fd, rxbuf, sizeof(rxbuf));
        if (n < 0) break;
        if (n == 0) {

            vTaskDelay(pdMS_TO_TICKS(200));
            continue;
        }
        for (int i = 0; i < n; i++) {
            char c = rxbuf[i];
            if (c == '\n' || c == '\r') {
                if (rxline_len > 0) {
                    rxline[rxline_len] = '\0';
                    if (strncmp(rxline, "LOC ", 4) == 0) {
                        handle_loc_line(rxline);
                    } else if (strcmp(rxline, "SYNC") == 0) {
                        do_manual_sync(fd);
                    } else {
                        handle_ack_line(fd, rxline);
                        if (s_backlog_truncated && s_pending_count == 0) {

                            s_suppress_stream = true;
                            offer_backlog_batch(fd);
                            s_suppress_stream = false;
                        }
                    }
                    rxline_len = 0;
                }
            } else if (rxline_len < sizeof(rxline) - 1) {
                rxline[rxline_len++] = c;
            }
        }
    }

connection_broken:
    s_active_fd = -1;
    if (s_conn_broken) {
        ESP_LOGW(TAG, "conexao derrubada de proposito apos falha de envio (fd=%d) — o celular reconecta e o "
                      "backlog e reoferecido do zero", fd);
    }
    ESP_LOGI(TAG, "conexao encerrada (fd=%d)", fd);
    close(fd);
    vTaskDelete(NULL);
}

static void spp_event_handler(uint16_t e, void *p)
{
    esp_spp_cb_event_t event = (esp_spp_cb_event_t)e;
    esp_spp_cb_param_t *param = (esp_spp_cb_param_t *)p;

    switch (event) {
    case ESP_SPP_INIT_EVT:
        if (param->init.status == ESP_SPP_SUCCESS) {
            ESP_LOGI(TAG, "SPP iniciado, registrando modo VFS");
            esp_spp_vfs_register();
        } else {
            ESP_LOGE(TAG, "falha ao iniciar SPP: status=%d", param->init.status);
        }
        break;

    case ESP_SPP_VFS_REGISTER_EVT:
        if (param->vfs_register.status == ESP_SPP_SUCCESS) {
            esp_spp_start_srv(ESP_SPP_SEC_AUTHENTICATE, ESP_SPP_ROLE_SLAVE, 0, "WARDRIVER_SPP");
        } else {
            ESP_LOGE(TAG, "falha ao registrar VFS do SPP: status=%d", param->vfs_register.status);
        }
        break;

    case ESP_SPP_START_EVT:
        if (param->start.status == ESP_SPP_SUCCESS) {
            esp_bt_gap_set_device_name(CONFIG_WARDRIVER_BT_DEVICE_NAME);
            esp_bt_gap_set_scan_mode(ESP_BT_CONNECTABLE, ESP_BT_GENERAL_DISCOVERABLE);
            ESP_LOGI(TAG, "servidor SPP no ar como \"%s\", aguardando conexao...",
                     CONFIG_WARDRIVER_BT_DEVICE_NAME);
        } else {
            ESP_LOGE(TAG, "falha ao subir servidor SPP: status=%d", param->start.status);
        }
        break;

    case ESP_SPP_SRV_OPEN_EVT:
        if (param->srv_open.status == ESP_SPP_SUCCESS) {
            ESP_LOGI(TAG, "celular conectado, enviando backlog...");
            xTaskCreate(spp_connection_task, "bt_sync_conn", 4096,
                        (void *)(intptr_t)param->srv_open.fd, 5, NULL);
        }
        break;

    case ESP_SPP_CLOSE_EVT:
        ESP_LOGI(TAG, "conexao SPP encerrada");
        break;

    default:
        break;
    }
}

static void spp_stack_cb(esp_spp_cb_event_t event, esp_spp_cb_param_t *param)
{
    work_dispatch(spp_event_handler, event, param, sizeof(esp_spp_cb_param_t));
}

static void gap_cb(esp_bt_gap_cb_event_t event, esp_bt_gap_cb_param_t *param)
{
    switch (event) {
    case ESP_BT_GAP_PIN_REQ_EVT: {
        esp_bt_pin_code_t pin_code = {'1', '2', '3', '4'};
        esp_bt_gap_pin_reply(param->pin_req.bda, true, 4, pin_code);
        break;
    }
    case ESP_BT_GAP_AUTH_CMPL_EVT:
        if (param->auth_cmpl.stat == ESP_BT_STATUS_SUCCESS) {
            ESP_LOGI(TAG, "pareamento OK: %s", param->auth_cmpl.device_name);
        } else {
            ESP_LOGW(TAG, "falha no pareamento: status=%d", param->auth_cmpl.stat);
        }
        break;
    default:
        break;
    }
}

void bt_spp_sync_set_storage_root(const char *mount_point)
{
    strncpy(s_storage_root, mount_point, sizeof(s_storage_root) - 1);
    s_storage_root[sizeof(s_storage_root) - 1] = '\0';
}

void bt_spp_sync_set_rotate_backend(bt_spp_sync_rotate_fn_t rotate_fn,
                                     char *current_session_buf, size_t current_session_buf_len)
{
    s_rotate_fn = rotate_fn;
    s_current_session_buf = current_session_buf;
    s_current_session_buf_len = current_session_buf_len;
}

void bt_spp_sync_set_current_session(const char *session_id)
{
    if (session_id == NULL || session_id[0] == '\0') {
        s_current_session_file[0] = '\0';
        s_current_session_id[0] = '\0';
        return;
    }
    snprintf(s_current_session_file, sizeof(s_current_session_file), "%s.wdr", session_id);
    strncpy(s_current_session_id, session_id, sizeof(s_current_session_id) - 1);
    s_current_session_id[sizeof(s_current_session_id) - 1] = '\0';
}

esp_err_t bt_spp_sync_init(void)
{
    s_write_mutex = xSemaphoreCreateMutex();
    if (s_write_mutex == NULL) return ESP_ERR_NO_MEM;

    s_work_queue = xQueueCreate(10, sizeof(work_msg_t));
    if (s_work_queue == NULL) return ESP_ERR_NO_MEM;
    xTaskCreate(work_task, "bt_spp_work", 4096, NULL, 10, NULL);

    ESP_ERROR_CHECK(esp_bt_controller_mem_release(ESP_BT_MODE_BLE));

    esp_bt_controller_config_t bt_cfg = BT_CONTROLLER_INIT_CONFIG_DEFAULT();
    esp_err_t ret = esp_bt_controller_init(&bt_cfg);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "falha ao inicializar o controlador BT: %s", esp_err_to_name(ret));
        return ret;
    }

    ret = esp_bt_controller_enable(ESP_BT_MODE_CLASSIC_BT);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "falha ao habilitar o controlador BT: %s", esp_err_to_name(ret));
        return ret;
    }

    esp_bluedroid_config_t bluedroid_cfg = BT_BLUEDROID_INIT_CONFIG_DEFAULT();
    ret = esp_bluedroid_init_with_cfg(&bluedroid_cfg);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "falha ao inicializar o Bluedroid: %s", esp_err_to_name(ret));
        return ret;
    }

    ret = esp_bluedroid_enable();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "falha ao habilitar o Bluedroid: %s", esp_err_to_name(ret));
        return ret;
    }

    ESP_ERROR_CHECK(esp_bt_gap_register_callback(gap_cb));
    ESP_ERROR_CHECK(esp_spp_register_callback(spp_stack_cb));

    esp_bt_pin_type_t pin_type = ESP_BT_PIN_TYPE_VARIABLE;
    esp_bt_pin_code_t pin_code;
    esp_bt_gap_set_pin(pin_type, 0, pin_code);

    esp_spp_cfg_t spp_cfg = BT_SPP_DEFAULT_CONFIG();
    ret = esp_spp_enhanced_init(&spp_cfg);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "falha ao inicializar o SPP: %s", esp_err_to_name(ret));
        return ret;
    }

    return ESP_OK;
}
