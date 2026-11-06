#pragma once
#include "freertos/FreeRTOS.h"
SemaphoreHandle_t xSemaphoreCreateMutex(void);
int xSemaphoreTake(SemaphoreHandle_t m, uint32_t t);
int xSemaphoreGive(SemaphoreHandle_t m);
