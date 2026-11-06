idf_component_register(
    SRCS "oled_display.c" "font5x7.c"
    INCLUDE_DIRS "include"
    REQUIRES driver esp_timer
)
