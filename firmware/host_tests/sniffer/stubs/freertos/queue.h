#pragma once
#include "freertos/FreeRTOS.h"
#ifndef pdTRUE
#define pdTRUE 1
#endif
typedef struct FakeQueue *QueueHandle_t;
QueueHandle_t xQueueCreate(uint32_t len, uint32_t item_size);
int xQueueSend(QueueHandle_t q, const void *item, uint32_t ticks);
int xQueueReceive(QueueHandle_t q, void *item, uint32_t ticks);
