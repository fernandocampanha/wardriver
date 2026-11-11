CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; font-src 'self'; "
       "img-src 'self' data: https://tile.openstreetmap.org https://*.tile.openstreetmap.org; "
       "base-uri 'none'; form-action 'self'; frame-ancestors 'none'")

SECURITY_HEADERS = [
    (b"content-security-policy", CSP.encode()),
    (b"x-content-type-options", b"nosniff"),
    (b"x-frame-options", b"DENY"),
    (b"referrer-policy", b"strict-origin-when-cross-origin"),
    (b"x-robots-tag", b"noindex, nofollow, noarchive"),
    (b"cross-origin-opener-policy", b"same-origin"),
    (b"permissions-policy", b"geolocation=(self), camera=(), microphone=()"),
]

class SecurityHeadersMiddleware:
    def __init__(self, app, trust_proxy: bool = False):
        self.app, self.trust_proxy = app, trust_proxy

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        https = self.trust_proxy and (dict(scope["headers"]).get(b"x-forwarded-proto") == b"https")

        async def send_wrapper(message):
            if message["type"] == "http.response.start":
                names = {k.lower() for k, _ in message["headers"]}
                extra = [(k, v) for k, v in SECURITY_HEADERS if k not in names]
                if https:
                    extra.append((b"strict-transport-security", b"max-age=31536000"))
                message = {**message, "headers": [*message["headers"], *extra]}
            await send(message)

        await self.app(scope, receive, send_wrapper)
