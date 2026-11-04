from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.orm import Session

from ..db import get_db
from ..deps import Principal, client_ip_hash, get_current
from . import service

router = APIRouter(prefix="/v1", tags=["activation"])


class ActivateIn(BaseModel):
    model_config = ConfigDict(extra="forbid")  # a client cannot smuggle an edition or user id
    code: str = Field(min_length=1, max_length=64)


@router.post("/activation")
def activate(body: ActivateIn, request: Request, p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    return service.redeem(db, p.user.id, body.code, client_ip_hash(request))
