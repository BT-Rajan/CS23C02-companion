"""Settings come only from the environment / .env (never from code). Weak or placeholder secrets refuse to start."""
from functools import lru_cache

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_PLACEHOLDERS = ("change-me", "changeme", "generate-with", "<<", "example")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: str = "dev"  # dev | test | prod
    database_url: str
    jwt_secret: str
    code_pepper: str  # HMAC key for activation codes (keep outside the database)
    aux_pepper: str  # HMAC key for one-time codes and IP hashes

    email_backend: str = "console"  # smtp | console | memory
    smtp_host: str = "smtp.gmail.com"
    smtp_port: int = 587
    smtp_user: str = ""
    smtp_password: str = ""
    smtp_from: str = ""

    access_ttl_seconds: int = 900
    refresh_idle_days: int = 90
    max_sessions: int = 3
    refresh_grace_seconds: int = 10
    otp_ttl_seconds: int = 600
    otp_max_attempts: int = 5
    activation_ip_fail_limit: int = 100  # per IP per day; high on purpose: campus/hostel NATs share one IP

    argon_memory_kb: int = 65536
    argon_time_cost: int = 3

    trust_proxy_headers: bool = False  # only behind a proxy that overwrites X-Forwarded-For
    max_body_bytes: int = 65536
    content_dir: str = "./var/content"  # content-addressed public files; keep outside the web root
    content_signing_pubkey: str = ""  # base64 Ed25519 public key that signs packages
    db_pool_size: int = 10
    db_max_overflow: int = 20

    @model_validator(mode="after")
    def _validate(self):
        secrets_ = {"jwt_secret": self.jwt_secret, "code_pepper": self.code_pepper, "aux_pepper": self.aux_pepper}
        for name, v in secrets_.items():
            if len(v) < 32 or any(p in v.lower() for p in _PLACEHOLDERS):
                raise ValueError(f"{name} must be a random value of at least 32 characters (e.g. `openssl rand -hex 32`)")
        if len(set(secrets_.values())) != 3:
            raise ValueError("jwt_secret, code_pepper and aux_pepper must all be different")
        if self.email_backend not in ("smtp", "console", "memory"):
            raise ValueError("email_backend must be smtp, console or memory")
        if self.env == "prod":
            if self.email_backend != "smtp":
                raise ValueError("prod requires EMAIL_BACKEND=smtp")
            if not (self.smtp_user and self.smtp_password and self.smtp_from):
                raise ValueError("prod requires SMTP_USER, SMTP_PASSWORD and SMTP_FROM")
            if self.argon_memory_kb < 65536 or self.argon_time_cost < 3:
                raise ValueError("prod requires Argon2id memory >= 64 MiB and time cost >= 3")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
