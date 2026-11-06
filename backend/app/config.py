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
    # Anomaly re-verification (ADR 0004, K9). Any one signal triggers it; a trigger never locks the account.
    anomaly_signins_24h: int = 6         # new sign-ins (sessions) in 24 h
    anomaly_ips_24h: int = 4             # distinct sign-in addresses in 24 h (campus NATs share one address, so sign-ins count, not users)
    anomaly_evictions_7d: int = 4        # sessions pushed out by the device cap in 7 days (friends taking turns)
    reverify_window_days: int = 7        # sessions created this long after a trigger must also confirm
    support_flag_triggers: int = 3       # triggers in 30 days that flag the account for a person to review
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
    # Graded runs (ADR 0006): "http" = the separate sandbox service (required in prod); "local" = run the sandbox package in
    # this process (tests and development only); "off" = Check is unavailable.
    sandbox_mode: str = "off"
    sandbox_url: str = ""
    sandbox_key: str = ""  # hex, >= 32 bytes, shared with the sandbox service; signs requests and verifies its results
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
        if self.sandbox_mode not in ("off", "local", "http"):
            raise ValueError("sandbox_mode must be off, local or http")
        if self.sandbox_mode == "http" and (len(self.sandbox_key) < 64 or not self.sandbox_url.startswith(("https://", "http://"))):
            raise ValueError("sandbox_mode=http needs SANDBOX_URL and a SANDBOX_KEY of at least 32 bytes (hex)")
        if self.env == "prod":
            if self.sandbox_mode == "local":
                raise ValueError("prod must run student code in the separate sandbox service (SANDBOX_MODE=http)")
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
