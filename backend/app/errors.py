from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


class ApiError(Exception):
    """One error shape for everything: {"error": {"message", "code", "ref"?}}. Messages never echo user input."""

    def __init__(self, status: int, message: str, code: str = "error", ref: str | None = None, headers: dict | None = None):
        self.status, self.message, self.code, self.ref, self.headers = status, message, code, ref, headers or {}


def _body(message, code, ref=None, fields=None):
    e = {"message": message, "code": code}
    if ref:
        e["ref"] = ref
    if fields is not None:
        e["fields"] = fields
    return {"error": e}


def install(app):
    @app.exception_handler(ApiError)
    async def _api(_: Request, e: ApiError):
        return JSONResponse(_body(e.message, e.code, e.ref), e.status, headers=e.headers)

    @app.exception_handler(RequestValidationError)
    async def _val(_: Request, e: RequestValidationError):
        fields = sorted({str(x["loc"][-1]) for x in e.errors() if x.get("loc")})  # names only, never values
        return JSONResponse(_body("Please check the highlighted fields.", "invalid_request", fields=fields), 422)

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, e: StarletteHTTPException):
        msg = {404: "Not found.", 405: "Method not allowed."}.get(e.status_code, "Request failed.")
        return JSONResponse(_body(msg, "http_%d" % e.status_code), e.status_code, headers=getattr(e, "headers", None))
