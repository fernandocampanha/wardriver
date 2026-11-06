idf_component_register(
    SRCS "wifi_sniffer.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_wifi esp_event esp_timer esp_system
)
