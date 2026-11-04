from dataclasses import dataclass
from datetime import timedelta

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from .config import get_settings
from .db import get_db, utcnow
from .errors import ApiError
from .models import User, UserSession
from .security import decode_access_token, ip_hash

_bearer = HTTPBearer(auto_error=False)


def client_ip(request: Request) -> str:
    if get_settings().trust_proxy_headers:
        xff = request.headers.get("x-forwarded-for", "")
        if xff:
            return xff.split(",")[-1].strip()  # the hop our own proxy appended
    return request.client.host if request.client else "unknown"


def client_ip_hash(request: Request) -> str:
    return ip_hash(client_ip(request))


@dataclass
class Principal:
    user: User
    session: UserSession


def _unauth() -> ApiError:
    return ApiError(401, "Please sign in again.", "unauthenticated", headers={"WWW-Authenticate": "Bearer"})


def get_current(creds: HTTPAuthorizationCredentials | None = Depends(_bearer), db: Session = Depends(get_db)) -> Principal:
    """Every protected route depends on this. The session row is checked on each call, so revocation is immediate."""
    if creds is None:
        raise _unauth()
    claims = decode_access_token(creds.credentials)
    if not claims:
        raise _unauth()
    sess = db.get(UserSession, claims["sid"])
    now = utcnow()
    if (not sess or sess.user_id != claims["sub"] or sess.revoked_at is not None
            or sess.last_used_at < now - timedelta(days=get_settings().refresh_idle_days)):
        raise _unauth()
    user = db.get(User, sess.user_id)
    if not user or user.disabled_at is not None or user.email_verified_at is None:
        raise _unauth()
    if now - sess.last_used_at > timedelta(seconds=60):
        sess.last_used_at = now
        db.commit()
    return Principal(user, sess)
