





#include "gps_nmea.h"

#include <string.h>
#include <stdlib.h>

#include "driver/uart.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/semphr.h"
#include "sdkconfig.h"

static const char *TAG = "gps_nmea";

#define GPS_UART_RX_BUF_SIZE 512
#define GPS_LINE_MAX_LEN      96

static gps_data_t s_data;
static SemaphoreHandle_t s_data_mutex = NULL;
static uart_port_t s_uart_num;





static bool nmea_checksum_ok(const char *sentence)
{
    const char *star = strchr(sentence, '*');
    if (star == NULL || star == sentence) return false;

    uint8_t calc = 0;
    for (const char *p = sentence + 1; p < star; p++) {
        calc ^= (uint8_t)(*p);
    }
    uint8_t given = (uint8_t)strtol(star + 1, NULL, 16);
    return calc == given;
}




static int split_fields(char *buf, char **fields, int max_fields)
{
    int count = 0;
    char *p = buf;
    fields[count++] = p;
    while (*p != '\0' && count < max_fields) {
        if (*p == ',' || *p == '*') {
            *p = '\0';
            p++;
            fields[count++] = p;
        } else {
            p++;
        }
    }
    return count;
}

static double nmea_to_decimal(const char *nmea_val, char hemisphere)
{
    if (nmea_val[0] == '\0') return 0.0;

    double raw = atof(nmea_val);
    int degrees = (int)(raw / 100);
    double minutes = raw - (degrees * 100);
    double decimal = degrees + (minutes / 60.0);

    if (hemisphere == 'S' || hemisphere == 'W') {
        decimal = -decimal;
    }
    return decimal;
}





static void parse_rmc(const char *line)
{
    char buf[GPS_LINE_MAX_LEN];
    strncpy(buf, line, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';


    char *fields[13] = {0};
    int n = split_fields(buf, fields, 13);
    if (n < 10) return;

    bool valid = (fields[2][0] == 'A');

    xSemaphoreTake(s_data_mutex, portMAX_DELAY);
    strncpy(s_data.utc_time, fields[1], sizeof(s_data.utc_time) - 1);
    s_data.utc_time[sizeof(s_data.utc_time) - 1] = '\0';
    s_data.has_fix = valid;
    if (valid) {
        s_data.latitude  = nmea_to_decimal(fields[3], fields[4][0]);
        s_data.longitude = nmea_to_decimal(fields[5], fields[6][0]);
        s_data.speed_knots = strtof(fields[7], NULL);
        s_data.heading_deg = strtof(fields[8], NULL);
    }
    s_data.last_update_us = esp_timer_get_time();
    xSemaphoreGive(s_data_mutex);
}

static void parse_gga(const char *line)
{
    char buf[GPS_LINE_MAX_LEN];
    strncpy(buf, line, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';


    char *fields[15] = {0};
    int n = split_fields(buf, fields, 15);
    if (n < 10) return;

    xSemaphoreTake(s_data_mutex, portMAX_DELAY);
    s_data.fix_quality = (uint8_t)atoi(fields[6]);
    s_data.satellites  = (uint8_t)atoi(fields[7]);
    s_data.hdop        = strtof(fields[8], NULL);
    s_data.altitude_m  = strtof(fields[9], NULL);



    if (s_data.fix_quality > 0) {
        s_data.latitude  = nmea_to_decimal(fields[2], fields[3][0]);
        s_data.longitude = nmea_to_decimal(fields[4], fields[5][0]);
        s_data.has_fix = true;
    } else {
        s_data.has_fix = false;
    }
    s_data.last_update_us = esp_timer_get_time();
    xSemaphoreGive(s_data_mutex);
}







static void parse_gsv(const char *line)
{
    char buf[GPS_LINE_MAX_LEN];
    strncpy(buf, line, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';

    char *fields[20] = {0};
    int n = split_fields(buf, fields, 20);
    if (n < 4) return;

    int msg_num = atoi(fields[2]);
    int sats_in_view = atoi(fields[3]);

    xSemaphoreTake(s_data_mutex, portMAX_DELAY);
    if (msg_num <= 1) {
        s_data.sats_tracked = 0;
        s_data.best_snr_db = -1;
    }
    s_data.sats_in_view = (uint8_t)sats_in_view;

    for (int slot = 0; slot < 4; slot++) {
        int base = 4 + slot * 4;
        if (base + 3 >= n) break;
        const char *snr_str = fields[base + 3];
        if (snr_str[0] != '\0') {
            int snr = atoi(snr_str);
            s_data.sats_tracked++;
            if (snr > s_data.best_snr_db) s_data.best_snr_db = (int8_t)snr;
        }
    }
    s_data.last_update_us = esp_timer_get_time();
    xSemaphoreGive(s_data_mutex);
}

static void gps_process_sentence(const char *line)
{
    if (line[0] != '$') return;
    if (!nmea_checksum_ok(line)) return;

    size_t len = strlen(line);
    if (len < 6) return;


    if (strncmp(&line[3], "RMC", 3) == 0) {
        parse_rmc(line);
    } else if (strncmp(&line[3], "GGA", 3) == 0) {
        parse_gga(line);
    } else if (strncmp(&line[3], "GSV", 3) == 0) {
        parse_gsv(line);
    }
}





static void gps_uart_task(void *arg)
{
    uint8_t *rx_buf = malloc(GPS_UART_RX_BUF_SIZE);
    char line[GPS_LINE_MAX_LEN];
    size_t line_len = 0;

    if (rx_buf == NULL) {
        ESP_LOGE(TAG, "sem memoria para buffer de UART");
        vTaskDelete(NULL);
        return;
    }

    for (;;) {
        int n = uart_read_bytes(s_uart_num, rx_buf, GPS_UART_RX_BUF_SIZE - 1, pdMS_TO_TICKS(1000));
        for (int i = 0; i < n; i++) {
            char c = (char)rx_buf[i];
            if (c == '\n' || c == '\r') {
                if (line_len > 0) {
                    line[line_len] = '\0';
                    gps_process_sentence(line);
                    line_len = 0;
                }
            } else if (line_len < GPS_LINE_MAX_LEN - 1) {
                line[line_len++] = c;
            } else {
                line_len = 0;
            }
        }
    }
}





esp_err_t gps_nmea_init(void)
{
    s_data_mutex = xSemaphoreCreateMutex();
    if (s_data_mutex == NULL) return ESP_ERR_NO_MEM;

    memset(&s_data, 0, sizeof(s_data));
    s_data.best_snr_db = -1;
    s_uart_num = (uart_port_t)CONFIG_WARDRIVER_GPS_UART_NUM;

    uart_config_t cfg = {
        .baud_rate = CONFIG_WARDRIVER_GPS_BAUD_RATE,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };

    ESP_ERROR_CHECK(uart_driver_install(s_uart_num, GPS_UART_RX_BUF_SIZE * 2, 0, 0, NULL, 0));
    ESP_ERROR_CHECK(uart_param_config(s_uart_num, &cfg));
    ESP_ERROR_CHECK(uart_set_pin(s_uart_num,
                                  CONFIG_WARDRIVER_GPS_TX_GPIO,
                                  CONFIG_WARDRIVER_GPS_RX_GPIO,
                                  UART_PIN_NO_CHANGE, UART_PIN_NO_CHANGE));

    ESP_LOGI(TAG, "UART%d configurada (rx=%d tx=%d baud=%d)",
             s_uart_num, CONFIG_WARDRIVER_GPS_RX_GPIO, CONFIG_WARDRIVER_GPS_TX_GPIO,
             CONFIG_WARDRIVER_GPS_BAUD_RATE);
    return ESP_OK;
}

esp_err_t gps_nmea_start(void)
{
    BaseType_t ok = xTaskCreate(gps_uart_task, "gps_uart", 4096, NULL, 5, NULL);
    return ok == pdPASS ? ESP_OK : ESP_FAIL;
}

void gps_nmea_get_data(gps_data_t *out)
{
    xSemaphoreTake(s_data_mutex, portMAX_DELAY);
    *out = s_data;
    xSemaphoreGive(s_data_mutex);
}
