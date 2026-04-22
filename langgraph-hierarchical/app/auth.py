from pydantic import BaseModel, EmailStr
from fastapi import APIRouter, HTTPException, status

from .security import (
    TokenResponse,
    generate_refresh_token,
    generate_token,
    verify_token,
)


router = APIRouter(prefix="/auth", tags=["auth"])


class LoginRequest(BaseModel):
    username: str
    password: str
    tenant_id: str | None = None


class RegisterRequest(BaseModel):
    username: str
    password: str
    tenant_id: str
    email: EmailStr | None = None


class RefreshRequest(BaseModel):
    refresh_token: str


class RegisterResponse(BaseModel):
    username: str
    tenant_id: str
    role: str
    status: str


USERS: dict[str, dict[str, str]] = {
    "admin": {
        "password": "admin123",
        "tenant_id": "smoke_tenant",
        "role": "ADMIN",
    },
    "user1": {
        "password": "admin123",
        "tenant_id": "tenant-1",
        "role": "USER",
    },
}


@router.post("/login", response_model=TokenResponse)
def login(request: LoginRequest) -> TokenResponse:
    user = USERS.get(request.username)
    if not user or user["password"] != request.password:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials")

    tenant_id = request.tenant_id or user["tenant_id"]
    access_token = generate_token(request.username, tenant_id, user["role"])
    refresh_token = generate_refresh_token(request.username, tenant_id)
    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token,
        expires_in=24 * 60 * 60,
    )


@router.post("/register", response_model=RegisterResponse)
def register(request: RegisterRequest) -> RegisterResponse:
    if request.username in USERS:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="User already exists")

    USERS[request.username] = {
        "password": request.password,
        "tenant_id": request.tenant_id,
        "role": "USER",
    }
    return RegisterResponse(
        username=request.username,
        tenant_id=request.tenant_id,
        role="USER",
        status="created",
    )


@router.post("/refresh", response_model=TokenResponse)
def refresh(request: RefreshRequest) -> TokenResponse:
    payload = verify_token(request.refresh_token)
    user = USERS.get(payload.sub)
    role = user["role"] if user else payload.role
    access_token = generate_token(payload.sub, payload.tenant_id, role)
    refresh_token = generate_refresh_token(payload.sub, payload.tenant_id)
    return TokenResponse(
        access_token=access_token,
        refresh_token=refresh_token,
        expires_in=24 * 60 * 60,
    )