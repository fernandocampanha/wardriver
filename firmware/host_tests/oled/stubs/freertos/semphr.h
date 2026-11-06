#pragma once
#include "FreeRTOS.h"
SemaphoreHandle_t xSemaphoreCreateRecursiveMutex(void);
int xSemaphoreTakeRecursive(SemaphoreHandle_t m, uint32_t t);
int xSemaphoreGiveRecursive(SemaphoreHandle_t m);
