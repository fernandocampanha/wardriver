idf_component_register(
    SRCS "phone_gps.c"
    INCLUDE_DIRS "include"
    REQUIRES gps_nmea esp_timer
)
