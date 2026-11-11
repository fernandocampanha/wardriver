#pragma once

#include <stddef.h>
#include <stdbool.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

esp_err_t flash_storage_init(void);

bool flash_storage_is_mounted(void);

const char *flash_storage_mount_point(void);

esp_err_t flash_storage_start_session(char *session_id_out, size_t out_len);

esp_err_t flash_storage_write_record(const void *data, size_t len);

esp_err_t flash_storage_flush(void);

esp_err_t flash_storage_get_usage(size_t *used_bytes, size_t *total_bytes);

esp_err_t flash_storage_rotate_session(char *old_session_id_out, size_t old_len,
                                        char *new_session_id_out, size_t new_len);

#ifdef __cplusplus
}
#endif
