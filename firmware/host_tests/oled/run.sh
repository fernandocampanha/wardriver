#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
proj="$(cd "$here/../.." && pwd)"
rm -f "$here/harness"
gcc -std=gnu11 -Wall -Wextra -Wno-unused-function -Wno-unused-variable -fsanitize=address,undefined -g \
    -I "$here/stubs" -I "$proj/components/oled_display/include" -I "$proj/components/oled_display" \
    -o "$here/harness" "$here/harness.c" "$proj/components/oled_display/font5x7.c"
"$here/harness"
