#pragma once
#include <stdint.h>
#define pdMS_TO_TICKS(x) ((uint32_t)(x))
#define portMAX_DELAY 0xFFFFFFFFu
#define pdPASS 1
typedef void *TaskHandle_t; typedef void *SemaphoreHandle_t;
