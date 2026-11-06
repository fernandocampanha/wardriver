

import hashlib
import hmac
import secrets
import threading
import time
from typing import Dict, List
_SCRYPT_N, _SCRYPT_R, _SCRYPT_P = 2 ** 14, 8, 1
def new_token() -> str:
    return secrets.token_urlsafe(32)

def token_sha256(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()

def verify_token(token: str, expected_sha256_hex: str) -> bool:
    if not token or not expected_sha256_hex:
        return False
    return hmac.compare_digest(token_sha256(token), expected_sha256_hex.strip().lower())

def hash_password(password: str, salt: bytes = b"") -> str:

    salt = salt or secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P, dklen=32)
    return f"scrypt${_SCRYPT_N}${_SCRYPT_R}${_SCRYPT_P}${salt.hex()}${digest.hex()}"

def verify_password(password: str, stored: str) -> bool:
    try:
        algo, n, r, p, salt_hex, hash_hex = stored.strip().split("$")
        if algo != "scrypt":
            return False
        digest = hashlib.scrypt(password.encode("utf-8"), salt=bytes.fromhex(salt_hex), n=int(n), r=int(r), p=int(p),
                                dklen=len(hash_hex) // 2)
        return hmac.compare_digest(digest.hex(), hash_hex)
    except (ValueError, TypeError):
        return False
class RateLimiter:

    def __init__(self, window_s: float = 60.0):
        self.window_s = window_s
        self._hits: Dict[str, List[float]] = {}
        self._lock = threading.Lock()

    def retry_after(self, key: str, limit: int) -> int:

        now = time.monotonic()
        with self._lock:
            recent = [t for t in self._hits.get(key, []) if now - t < self.window_s]
            if len(recent) >= limit:
                self._hits[key] = recent
                return int(self.window_s - (now - recent[0])) + 1
            recent.append(now)
            self._hits[key] = recent
            if len(self._hits) > 20_000:
                self._hits.clear()
        return 0
