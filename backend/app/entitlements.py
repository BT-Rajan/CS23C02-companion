from sqlalchemy import select
from sqlalchemy.orm import Session

from .errors import ApiError
from .models import Entitlement


def not_entitled() -> ApiError:
    """One answer for 'no such edition' and 'not yours', so editions cannot be probed."""
    return ApiError(403, "This book isn't activated on your account.", "not_entitled")


def entitled_edition_ids(db: Session, user_id: str) -> list[str]:
    return list(db.scalars(select(Entitlement.edition_id).where(Entitlement.user_id == user_id, Entitlement.revoked_at.is_(None))))


def entitled_edition(db: Session, user_id: str, requested: str | None) -> str:
    """The edition always comes from the user's own entitlements. A client-supplied id is only ever checked against them."""
    ids = entitled_edition_ids(db, user_id)
    if requested is None:
        if len(ids) == 1:
            return ids[0]
        if not ids:
            raise not_entitled()
        raise ApiError(400, "Please choose a book.", "edition_required")
    if requested not in ids:
        raise not_entitled()
    return requested
