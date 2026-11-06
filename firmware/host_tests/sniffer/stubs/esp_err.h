#pragma once
#include <stdio.h>
#include <stdlib.h>
typedef int esp_err_t;
#define ESP_OK 0
#define ESP_FAIL -1
#define ESP_ERR_NO_MEM 0x101
#define ESP_ERR_INVALID_STATE 0x103
static inline const char *esp_err_to_name(esp_err_t e) { return e == 0 ? "OK" : "ERR"; }
#define ESP_ERROR_CHECK(x) do { esp_err_t _e = (x); if (_e != ESP_OK) { printf("ESP_ERROR_CHECK falhou (%d) em %s:%d\n", _e, __FILE__, __LINE__); abort(); } } while (0)
