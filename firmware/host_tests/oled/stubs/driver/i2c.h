#pragma once
#include <stdint.h>
#include <stddef.h>
#include "esp_err.h"
#include "driver/gpio.h"
#define I2C_NUM_0 0
typedef enum { I2C_MODE_MASTER = 1 } i2c_mode_t;
typedef struct { i2c_mode_t mode; int sda_io_num; int scl_io_num; gpio_pullup_t sda_pullup_en; gpio_pullup_t scl_pullup_en; struct { uint32_t clk_speed; } master; } i2c_config_t;
esp_err_t i2c_param_config(int port, const i2c_config_t *c);
esp_err_t i2c_driver_install(int port, i2c_mode_t mode, size_t a, size_t b, int c);
esp_err_t i2c_driver_delete(int port);
esp_err_t i2c_master_write_to_device(int port, uint8_t addr, const uint8_t *buf, size_t len, uint32_t ticks);
