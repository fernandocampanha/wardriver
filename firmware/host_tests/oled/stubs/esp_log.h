#pragma once
#include <stdio.h>
void harness_log(char lvl, const char *fmt, ...);
#define ESP_LOGI(t, f, ...) harness_log('I', f, ##__VA_ARGS__)
#define ESP_LOGW(t, f, ...) harness_log('W', f, ##__VA_ARGS__)
#define ESP_LOGE(t, f, ...) harness_log('E', f, ##__VA_ARGS__)
