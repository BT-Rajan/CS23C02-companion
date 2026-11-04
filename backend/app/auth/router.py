from fastapi import APIRouter, BackgroundTasks, Depends, Request
from fastapi.responses import JSONResponse
from starlette.background import BackgroundTask
from pydantic import BaseModel, ConfigDict, EmailStr, Field
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .. import mail, ratelimit
from ..audit import audit
from ..db import get_db, utcnow
from ..deps import Principal, client_ip_hash, get_current
from ..errors import ApiError
from ..models import OneTimeCode, User
from ..security import (hash_password, needs_rehash, sha256_hex, spend_password_time, validate_password, verify_password)
from . import service

router = APIRouter(prefix="/v1/auth", tags=["auth"])

BAD_CODE = "That code didn't work. Check it, or ask for a new one."
BAD_LOGIN = "Email or password is incorrect."


class _In(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Credentials(_In):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)


class VerifyIn(_In):
    email: EmailStr
    code: str = Field(pattern=r"^\d{6}$")
    device_label: str = Field(default="", max_length=80)


class LoginIn(Credentials):
    device_label: str = Field(default="", max_length=80)


class RefreshIn(_In):
    refresh_token: str = Field(min_length=20, max_length=200)


class ForgotIn(_In):
    email: EmailStr


class ResetIn(_In):
    email: EmailStr
    code: str = Field(pattern=r"^\d{6}$")
    new_password: str = Field(min_length=1, max_length=128)


def _pw_or_422(pw: str, email: str):
    try:
        validate_password(pw, email)
    except ValueError as e:
        raise ApiError(422, str(e), "weak_password")


@router.post("/register", status_code=202)
def register(body: Credentials, request: Request, bg: BackgroundTasks, db: Session = Depends(get_db)):
    """Always answers 202 'code_sent' (same body, same work) whether or not the email already has an account."""
    iph = client_ip_hash(request)
    ratelimit.enforce(f"reg-ip:{iph}", 20, 3600)
    email = body.email.lower()
    _pw_or_422(body.password, email)
    pw_hash = hash_password(body.password)  # always hashed, so timing is the same in every branch
    user = db.scalar(select(User).where(User.email == email))
    if user is None:
        try:
            db.add(User(email=email, pw_hash=pw_hash, created_at=utcnow()))
            db.commit()
            user_state = "new"
        except IntegrityError:  # a parallel request created it first
            db.rollback()
            user_state = "unverified"
    elif user.email_verified_at is None:
        user.pw_hash = pw_hash  # whoever can read the emailed code owns this address
        db.commit()
        user_state = "unverified"
    else:
        user_state = "verified"
    if service.may_send(email, "verify" if user_state != "verified" else "exists"):
        if user_state == "verified":
            bg.add_task(mail.account_exists_email, email)
        else:
            bg.add_task(mail.verification_email, email, service.issue_code(db, email, "verify"))
    return {"status": "code_sent"}


@router.post("/verify-email")
def verify_email(body: VerifyIn, request: Request, db: Session = Depends(get_db)):
    iph = client_ip_hash(request)
    ratelimit.enforce(f"verify-ip:{iph}", 100, 3600)
    email = body.email.lower()
    user = db.scalar(select(User).where(User.email == email))
    ok = service.verify_code(db, email, "verify", body.code)
    if not ok or user is None or user.disabled_at is not None:
        audit("verify_email", "fail", None, "email:" + sha256_hex(email)[:12], iph)
        raise ApiError(400, BAD_CODE, "bad_code")
    if user.email_verified_at is None:
        user.email_verified_at = utcnow()
        db.commit()
    audit("verify_email", "success", user.id, None, iph)
    return service.create_session(db, user, body.device_label, iph)


@router.post("/login")
def login(body: LoginIn, request: Request, bg: BackgroundTasks, db: Session = Depends(get_db)):
    iph = client_ip_hash(request)
    email = body.email.lower()
    eh = sha256_hex(email)[:32]
    if (ratelimit.count(f"login-fail:{eh}:{iph}", 900) >= 5 or ratelimit.count(f"login-fail:{eh}", 900) >= 20
            or ratelimit.count(f"login-fail-ip:{iph}", 900) >= 200):
        raise ratelimit.too_many(900)
    user = db.scalar(select(User).where(User.email == email))
    ok = False
    if user is not None:
        ok = verify_password(user.pw_hash, body.password)
    else:
        spend_password_time(body.password)
    if not ok or user.disabled_at is not None:
        for k in (f"login-fail:{eh}:{iph}", f"login-fail:{eh}", f"login-fail-ip:{iph}"):
            ratelimit.record(k)
        audit("login", "fail", user.id if user else None, None, iph)
        raise ApiError(401, BAD_LOGIN, "bad_credentials")
    if user.email_verified_at is None:
        # Background tasks added via `bg` are dropped when a route raises, so the email rides on the 403 response itself.
        task = BackgroundTask(mail.verification_email, email, service.issue_code(db, email, "verify")) if service.may_send(email, "verify") else None
        return JSONResponse({"error": {"message": "Please verify your email. We have sent you a new code.", "code": "email_unverified"}},
                            status_code=403, background=task)
    if needs_rehash(user.pw_hash):
        user.pw_hash = hash_password(body.password)
        db.commit()
    audit("login", "success", user.id, None, iph)
    return service.create_session(db, user, body.device_label, iph)


@router.post("/refresh")
def refresh(body: RefreshIn, request: Request, db: Session = Depends(get_db)):
    iph = client_ip_hash(request)
    ratelimit.enforce(f"refresh-ip:{iph}", 300, 3600)
    out = service.rotate(db, body.refresh_token, iph)
    if out is None:
        raise ApiError(401, "Please sign in again.", "unauthenticated")
    return out


@router.post("/logout", status_code=204)
def logout(request: Request, p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    service.revoke(db, p.session, "logout")
    audit("logout", "success", p.user.id, f"session:{p.session.id}", client_ip_hash(request), db=db)
    db.commit()


@router.post("/forgot", status_code=202)
def forgot(body: ForgotIn, request: Request, bg: BackgroundTasks, db: Session = Depends(get_db)):
    iph = client_ip_hash(request)
    ratelimit.enforce(f"forgot-ip:{iph}", 20, 3600)
    email = body.email.lower()
    user = db.scalar(select(User).where(User.email == email, User.disabled_at.is_(None)))
    if user is not None and service.may_send(email, "reset"):
        bg.add_task(mail.reset_email, email, service.issue_code(db, email, "reset"))
    return {"status": "code_sent"}  # identical whether or not the account exists


@router.post("/reset")
def reset(body: ResetIn, request: Request, db: Session = Depends(get_db)):
    iph = client_ip_hash(request)
    ratelimit.enforce(f"reset-ip:{iph}", 30, 3600)
    email = body.email.lower()
    _pw_or_422(body.new_password, email)
    user = db.scalar(select(User).where(User.email == email, User.disabled_at.is_(None)))
    if not service.verify_code(db, email, "reset", body.code) or user is None:
        audit("password_reset", "fail", None, "email:" + sha256_hex(email)[:12], iph)
        raise ApiError(400, BAD_CODE, "bad_code")
    user.pw_hash = hash_password(body.new_password)
    if user.email_verified_at is None:
        user.email_verified_at = utcnow()  # they just proved control of the address
    service.revoke_all(db, user.id, "password_reset")  # every device signs out
    now = utcnow()
    for r in db.scalars(select(OneTimeCode).where(OneTimeCode.email == email, OneTimeCode.used_at.is_(None))):
        r.used_at = now
    audit("password_reset", "success", user.id, None, iph, db=db)
    db.commit()
    return {"status": "ok"}
