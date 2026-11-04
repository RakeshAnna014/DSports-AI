"""Validation of the customer's existing DSports access token (HMAC, same secret as the Java backend).

The authenticated identity comes only from the verified token. Nothing in a request body is ever used as an identity.
This module authenticates; it does not authorize commerce resources: the Java backend stays the authorization boundary.
"""
from __future__ import annotations

import hashlib
import hmac
import uuid
from dataclasses import dataclass, field

import jwt
from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config.settings import Settings, get_settings
from app.errors import ApiException

_bearer = HTTPBearer(auto_error=False)

# The Java backend (jjwt) picks the HMAC strength from the secret length: 32-47 bytes -> HS256, 48-63 -> HS384,
# 64+ -> HS512. All three are symmetric and verified with the same shared secret, so accepting the family is safe;
# "none" and every asymmetric algorithm stay rejected (there is no public key, so no algorithm-confusion path).
_HMAC_ALGORITHMS = ["HS256", "HS384", "HS512"]


@dataclass(frozen=True)
class Principal:
    user_id: uuid.UUID = field(repr=False)  # never logged or returned
    roles: tuple[str, ...]
    customer_hash: str
    token: str = field(repr=False)  # forwarded as-is to DSports; never logged

    @property
    def is_admin(self) -> bool:
        return "ADMIN" in self.roles


def customer_hash(user_id: uuid.UUID | str, key: str) -> str:
    """Stable, non-reversible customer reference for logs, usage and audit (HMAC-SHA256, truncated)."""
    return hmac.new(key.encode("utf-8"), str(user_id).encode("utf-8"), hashlib.sha256).hexdigest()[:32]


def verify_token(token: str, settings: Settings) -> Principal:
    try:
        claims = jwt.decode(
            token,
            settings.jwt_secret.get_secret_value().encode("utf-8"),
            algorithms=_HMAC_ALGORITHMS,  # "none" and asymmetric algorithms are never accepted
            issuer=settings.jwt_issuer, audience=settings.jwt_audience,   # a validly signed token for another issuer or audience is not ours
            options={"require": ["exp", "sub", "iss", "aud"]},
        )
    except jwt.ExpiredSignatureError:
        raise ApiException(401, "TOKEN_EXPIRED", "The access token has expired") from None
    except jwt.PyJWTError:
        raise ApiException(401, "INVALID_TOKEN", "The access token is not valid") from None

    try:
        user_id = uuid.UUID(str(claims["sub"]))
    except ValueError:
        raise ApiException(401, "INVALID_TOKEN", "The access token is not valid") from None

    roles = claims.get("roles", [])
    if not isinstance(roles, list):
        roles = []
    key = settings.customer_hash_key.get_secret_value()
    return Principal(
        user_id=user_id,
        roles=tuple(str(r) for r in roles),
        customer_hash=customer_hash(user_id, key),
        token=token,
    )


def current_principal(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    settings: Settings = Depends(get_settings),
) -> Principal:
    if credentials is None or credentials.scheme.lower() != "bearer" or not credentials.credentials:
        raise ApiException(401, "UNAUTHORIZED", "Authentication is required")
    principal = verify_token(credentials.credentials, settings)
    request.state.customer_hash = principal.customer_hash
    return principal


def optional_principal(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    settings: Settings = Depends(get_settings),
) -> Principal | None:
    """Guests (no Authorization header) are allowed; a PRESENT but invalid or expired token is still a 401, never a silent downgrade."""
    if credentials is None:
        if request.headers.get("authorization"):  # e.g. "Basic ...": present but not a bearer token
            raise ApiException(401, "INVALID_TOKEN", "The access token is not valid")
        return None
    return current_principal(request, credentials, settings)
