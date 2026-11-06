#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
proj="$(cd "$here/../.." && pwd)"
rm -f "$here/harness"
gcc -std=gnu11 -Wall -Wextra -Wno-unused-function -Wno-unused-variable -Wno-zero-length-array -fsanitize=address,undefined -g \
    -include sdkconfig.h -I "$here/stubs" -I "$here/../oled/stubs" -I "$proj/components/wifi_sniffer/include" \
    -I "$proj/components/wifi_sniffer" -o "$here/harness" "$here/harness.c"
"$here/harness"
