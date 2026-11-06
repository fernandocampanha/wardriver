idf_component_register(
    SRCS "bt_spp_sync.c"
    INCLUDE_DIRS "include"
    REQUIRES bt esp_rom wardriver_proto phone_gps
)
