#pragma once

#include <stdbool.h>
#include <stddef.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

























































esp_err_t bt_spp_sync_init(void);


void bt_spp_sync_set_storage_root(const char *mount_point);



void bt_spp_sync_set_current_session(const char *session_id);




typedef esp_err_t (*bt_spp_sync_rotate_fn_t)(char *old_session_id_out, size_t old_len,
                                              char *new_session_id_out, size_t new_len);









void bt_spp_sync_set_rotate_backend(bt_spp_sync_rotate_fn_t rotate_fn,
                                     char *current_session_buf, size_t current_session_buf_len);






void bt_spp_sync_stream_record(const void *data, size_t len);







void bt_spp_sync_send_status_line(const char *text);






bool bt_spp_sync_is_connected(void);

#ifdef __cplusplus
}
#endif
