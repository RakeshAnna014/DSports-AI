from __future__ import annotations

import time
import uuid

import jwt
import pytest
from fastapi.testclient import TestClient

from app.config.settings import Settings
from app.main import create_app

JWT_SECRET = "unit-test-secret-unit-test-secret-0123456789"
JWT_ISSUER, JWT_AUDIENCE = "dsports", "dsports-api"
HASH_KEY = "unit-test-hash-key-0123456789"
USER_ID = "3f2b8f0e-6c1a-4d57-9a43-0d5a1b7c9e11"


def make_settings(**overrides) -> Settings:
    values = dict(JWT_SECRET=JWT_SECRET, AI_CUSTOMER_HASH_KEY=HASH_KEY,
                  DSPORTS_BASE_URL="http://127.0.0.1:9",  # nothing listens here: DSports is "down" in unit tests
                  AI_TIMEOUT_S=2)
    values.update(overrides)
    return Settings(_env_file=None, **values)


def make_token(sub: str = USER_ID, *, secret: str = JWT_SECRET, expires_in: int = 900, roles=("CUSTOMER",),
               algorithm: str = "HS256", **extra) -> str:
    now = int(time.time())
    claims = {"sub": sub, "roles": list(roles), "iss": JWT_ISSUER, "aud": JWT_AUDIENCE, "iat": now, "exp": now + expires_in, "jti": str(uuid.uuid4()), **extra}
    if sub is None:
        del claims["sub"]
    return jwt.encode(claims, secret, algorithm=algorithm)


_TOKENS: dict[str, str] = {}


def token_for(user_id: str) -> str:
    """One stable token per user for the whole test run (so the fake DSports can recognise it)."""
    if user_id not in _TOKENS:
        _TOKENS[user_id] = make_token(user_id, expires_in=3600 * 6)
    return _TOKENS[user_id]


@pytest.fixture(autouse=True)
def hermetic_environment(monkeypatch):
    """Unit tests must not depend on (or read) the developer's real credentials or AI_* configuration."""
    import os
    for name in list(os.environ):
        if name.startswith(("AI_", "GOOGLE_")) or name in ("JWT_SECRET", "DSPORTS_BASE_URL"):
            monkeypatch.delenv(name)


@pytest.fixture
def settings() -> Settings:
    return make_settings()


@pytest.fixture
def client(settings):
    with TestClient(create_app(settings), raise_server_exceptions=False) as c:
        yield c
