
idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS "."
    REQUIRES nvs_flash esp_wifi esp_event esp_netif esp_timer wardriver_proto
             wifi_sniffer gps_nmea phone_gps flash_storage oled_display bt_spp_sync
)
