#pragma once
#include "FreeRTOS.h"
void vTaskDelay(uint32_t ticks);
int xTaskCreate(void (*fn)(void *), const char *name, uint32_t stack, void *arg, int prio, TaskHandle_t *h);
