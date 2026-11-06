"""Provider interface for email. Gmail SMTP in production (settings from .env); console/memory only outside prod."""
import logging
import smtplib
import ssl
from email.message import EmailMessage

from .config import get_settings

log = logging.getLogger("mail")
OUTBOX: list[dict] = []  # memory backend (tests)


def send_email(to: str, subject: str, body: str) -> None:
    s = get_settings()
    if s.email_backend == "memory":
        OUTBOX.append({"to": to, "subject": subject, "body": body})
        return
    if s.email_backend == "console":
        print(f"\n--- EMAIL (dev only) to={to} subject={subject}\n{body}\n---")
        return
    msg = EmailMessage()
    msg["From"], msg["To"], msg["Subject"] = s.smtp_from, to, subject
    msg.set_content(body)
    try:
        with smtplib.SMTP(s.smtp_host, s.smtp_port, timeout=15) as smtp:
            smtp.starttls(context=ssl.create_default_context())
            smtp.login(s.smtp_user, s.smtp_password)
            smtp.send_message(msg)
    except Exception as e:  # never log the body (it holds a code) or credentials
        log.error("email send failed: %s", type(e).__name__)


def verification_email(to: str, code: str) -> None:
    send_email(to, "Your CS23C02 Companion verification code",
               f"Your verification code is {code}.\nIt expires in 10 minutes. If you did not ask for it, ignore this email.\n"
               "Not seeing it? Check your spam folder.")


def reset_email(to: str, code: str) -> None:
    send_email(to, "Your CS23C02 Companion password reset code",
               f"Your password reset code is {code}.\nIt expires in 10 minutes. If you did not ask for it, you can ignore this "
               "email; your password has not changed.")


def account_exists_email(to: str) -> None:
    send_email(to, "CS23C02 Companion: you already have an account",
               "Someone (hopefully you) tried to create an account with this email. You already have one: sign in, or choose "
               "'Forgot password' if you need to reset it. If this was not you, no action is needed.")


def reverify_email(to: str, code: str) -> None:
    send_email(to, "Confirm it's you: CS23C02 Companion",
               f"Your account was signed in on several devices or networks in a short time. To keep using it on this device, "
               f"enter the code in the app. Your code is {code}.\nIt expires in 10 minutes.\n\nIf this wasn't you, reset your password "
               "('Forgot password' in the app): that signs every device out. Your account is not locked.")
