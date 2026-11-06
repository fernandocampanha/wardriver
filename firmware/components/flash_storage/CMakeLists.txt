idf_component_register(
    SRCS "flash_storage.c"
    INCLUDE_DIRS "include"
    REQUIRES spiffs nvs_flash
)
