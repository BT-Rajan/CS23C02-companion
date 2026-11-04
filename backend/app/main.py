from fastapi import FastAPI, Request

from . import errors
from .activation.router import router as activation_router
from .auth.router import router as auth_router
from .config import get_settings
from .me import router as me_router


class _TooLarge(Exception):
    pass


class BodyLimit:
    """Rejects request bodies over the cap, including chunked ones with no Content-Length."""

    def __init__(self, app, max_bytes: int):
        self.app, self.max = app, max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        for k, v in scope["headers"]:
            if k == b"content-length":
                try:
                    if int(v) > self.max:
                        return await self._reject(send)
                except ValueError:
                    return await self._reject(send)
        total, started = 0, False

        async def limited():
            nonlocal total
            msg = await receive()
            if msg["type"] == "http.request":
                total += len(msg.get("body", b""))
                if total > self.max:
                    raise _TooLarge
            return msg

        async def tracking(msg):
            nonlocal started
            started = started or msg["type"] == "http.response.start"
            await send(msg)

        try:
            await self.app(scope, limited, tracking)
        except _TooLarge:
            if not started:
                await self._reject(send)

    @staticmethod
    async def _reject(send):
        body = b'{"error":{"message":"Request too large.","code":"too_large"}}'
        await send({"type": "http.response.start", "status": 413,
                    "headers": [(b"content-type", b"application/json"), (b"content-length", str(len(body)).encode())]})
        await send({"type": "http.response.body", "body": body})


def create_app() -> FastAPI:
    s = get_settings()
    app = FastAPI(title="CS23C02 Companion API", version="0.1.0",
                  docs_url=None if s.env == "prod" else "/docs", redoc_url=None, openapi_url=None if s.env == "prod" else "/openapi.json")
    errors.install(app)
    app.include_router(auth_router)
    app.include_router(activation_router)
    app.include_router(me_router)

    @app.get("/healthz", include_in_schema=False)
    def healthz():
        return {"ok": True}

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        r = await call_next(request)
        r.headers["Cache-Control"] = "no-store"
        r.headers["X-Content-Type-Options"] = "nosniff"
        r.headers["Referrer-Policy"] = "no-referrer"
        r.headers["Content-Security-Policy"] = "default-src 'none'; frame-ancestors 'none'" if s.env == "prod" else r.headers.get("content-security-policy", "")
        if s.env == "prod":
            r.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
        if not r.headers["Content-Security-Policy"]:
            del r.headers["Content-Security-Policy"]
        return r

    app.add_middleware(BodyLimit, max_bytes=s.max_body_bytes)
    return app


app = create_app()
