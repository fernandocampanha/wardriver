#pragma once
#include <stdint.h>
typedef enum { GPIO_MODE_INPUT_OUTPUT_OD = 6 } gpio_mode_t;
typedef enum { GPIO_PULLUP_DISABLE = 0, GPIO_PULLUP_ENABLE = 1 } gpio_pullup_t;
typedef enum { GPIO_PULLDOWN_DISABLE = 0 } gpio_pulldown_t;
typedef enum { GPIO_INTR_DISABLE = 0 } gpio_int_type_t;
typedef struct { uint64_t pin_bit_mask; gpio_mode_t mode; gpio_pullup_t pull_up_en; gpio_pulldown_t pull_down_en; gpio_int_type_t intr_type; } gpio_config_t;
int gpio_config(const gpio_config_t *c);
int gpio_set_level(int pin, uint32_t level);
int gpio_get_level(int pin);
