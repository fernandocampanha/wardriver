import os
import sys

import uvicorn

from .config import ConfigError, load_settings

def main() -> int:
    try:
        load_settings()
    except ConfigError as e:
        sys.stderr.write(f"erro de configuracao: {e}\n")
        return 2
    uvicorn.run("app.main:create_app", factory=True, host=os.environ.get("WARDRIVER_WEB_HOST", "127.0.0.1"),
                port=int(os.environ.get("PORT", "8080")), proxy_headers=False, server_header=False)
    return 0

if __name__ == "__main__":
    sys.exit(main())
