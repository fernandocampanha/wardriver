#include "flash_storage.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_spiffs.h"
#include "nvs.h"
#include "sdkconfig.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

static const char *TAG = "flash_storage";

static FILE *s_session_file = NULL;
static bool s_mounted = false;
static char s_mount_point[32];
static char s_current_session_id[32] = "";

static SemaphoreHandle_t s_file_mutex = NULL;

static esp_err_t open_new_session_locked(char *session_id_out, size_t out_len)
{
    uint32_t session_num = 0;
    nvs_handle_t nvs;
    bool nvs_ok = false;
    esp_err_t ret = nvs_open("wardriver", NVS_READWRITE, &nvs);
    if (ret == ESP_OK) {
        nvs_ok = true;
        nvs_get_u32(nvs, "session_num", &session_num);
        session_num++;
    } else {
        ESP_LOGW(TAG, "NVS indisponivel, sessao ficara marcada como 0 (%s)", esp_err_to_name(ret));
    }

    char path[64];
    snprintf(path, sizeof(path), "%s/sess_%04lu.wdr", s_mount_point, (unsigned long)session_num);

    s_session_file = fopen(path, "a");
    if (s_session_file == NULL) {
        ESP_LOGE(TAG, "nao foi possivel abrir %s para escrita (errno=%d: %s)",
                 path, errno, strerror(errno));
        if (nvs_ok) nvs_close(nvs);
        return ESP_FAIL;
    }

    if (nvs_ok) {
        nvs_set_u32(nvs, "session_num", session_num);
        nvs_commit(nvs);
        nvs_close(nvs);
    }

    snprintf(s_current_session_id, sizeof(s_current_session_id), "sess_%04lu", (unsigned long)session_num);
    if (session_id_out != NULL) {
        strncpy(session_id_out, s_current_session_id, out_len - 1);
        session_id_out[out_len - 1] = '\0';
    }
    ESP_LOGI(TAG, "sessao iniciada: %s", path);
    return ESP_OK;
}

esp_err_t flash_storage_init(void)
{
    strncpy(s_mount_point, CONFIG_WARDRIVER_FLASH_STORAGE_MOUNT_POINT, sizeof(s_mount_point) - 1);
    s_mount_point[sizeof(s_mount_point) - 1] = '\0';

    esp_vfs_spiffs_conf_t conf = {
        .base_path = s_mount_point,
        .partition_label = CONFIG_WARDRIVER_FLASH_STORAGE_PARTITION_LABEL,
        .max_files = 4,
        .format_if_mount_failed = true,
    };

    esp_err_t ret = esp_vfs_spiffs_register(&conf);
    if (ret != ESP_OK) {
        if (ret == ESP_ERR_NOT_FOUND) {
            ESP_LOGE(TAG, "particao \"%s\" nao encontrada em partitions.csv",
                     CONFIG_WARDRIVER_FLASH_STORAGE_PARTITION_LABEL);
        } else {
            ESP_LOGE(TAG, "falha ao montar SPIFFS: %s", esp_err_to_name(ret));
        }
        return ret;
    }

    size_t total = 0, used = 0;
    esp_err_t info_ret = esp_spiffs_info(conf.partition_label, &total, &used);
    if (info_ret == ESP_OK) {
        ESP_LOGI(TAG, "SPIFFS montado em %s: %u/%u KB usados",
                 s_mount_point, (unsigned)(used / 1024), (unsigned)(total / 1024));
    }

    s_file_mutex = xSemaphoreCreateMutex();
    if (s_file_mutex == NULL) {
        esp_vfs_spiffs_unregister(conf.partition_label);
        return ESP_ERR_NO_MEM;
    }

    s_mounted = true;
    return ESP_OK;
}

bool flash_storage_is_mounted(void)
{
    return s_mounted;
}

const char *flash_storage_mount_point(void)
{
    return s_mounted ? s_mount_point : NULL;
}

esp_err_t flash_storage_start_session(char *session_id_out, size_t out_len)
{
    if (!s_mounted) return ESP_ERR_INVALID_STATE;

    xSemaphoreTake(s_file_mutex, portMAX_DELAY);
    esp_err_t ret;
    if (s_session_file != NULL) {

        if (session_id_out != NULL) {
            strncpy(session_id_out, s_current_session_id, out_len - 1);
            session_id_out[out_len - 1] = '\0';
        }
        ret = ESP_OK;
    } else {
        ret = open_new_session_locked(session_id_out, out_len);
    }
    xSemaphoreGive(s_file_mutex);
    return ret;
}

esp_err_t flash_storage_write_record(const void *data, size_t len)
{
    if (s_session_file == NULL) return ESP_ERR_INVALID_STATE;

    xSemaphoreTake(s_file_mutex, portMAX_DELAY);
    esp_err_t ret = ESP_OK;
    if (s_session_file == NULL) {
        ret = ESP_ERR_INVALID_STATE;
    } else if (fwrite(data, 1, len, s_session_file) != len) {
        ret = ESP_FAIL;
    }
    xSemaphoreGive(s_file_mutex);
    return ret;
}

esp_err_t flash_storage_flush(void)
{
    if (s_session_file == NULL) return ESP_ERR_INVALID_STATE;

    xSemaphoreTake(s_file_mutex, portMAX_DELAY);
    esp_err_t ret = (s_session_file != NULL && fflush(s_session_file) == 0) ? ESP_OK : ESP_ERR_INVALID_STATE;
    xSemaphoreGive(s_file_mutex);
    return ret;
}

esp_err_t flash_storage_get_usage(size_t *used_bytes, size_t *total_bytes)
{
    if (!s_mounted) return ESP_ERR_INVALID_STATE;
    return esp_spiffs_info(CONFIG_WARDRIVER_FLASH_STORAGE_PARTITION_LABEL, total_bytes, used_bytes);
}

esp_err_t flash_storage_rotate_session(char *old_session_id_out, size_t old_len,
                                        char *new_session_id_out, size_t new_len)
{
    if (!s_mounted) return ESP_ERR_INVALID_STATE;

    xSemaphoreTake(s_file_mutex, portMAX_DELAY);

    if (old_session_id_out != NULL) {
        strncpy(old_session_id_out, s_current_session_id, old_len - 1);
        old_session_id_out[old_len - 1] = '\0';
    }
    if (s_session_file != NULL) {
        fflush(s_session_file);
        fclose(s_session_file);
        s_session_file = NULL;
    }

    esp_err_t ret = open_new_session_locked(new_session_id_out, new_len);
    xSemaphoreGive(s_file_mutex);
    return ret;
}
