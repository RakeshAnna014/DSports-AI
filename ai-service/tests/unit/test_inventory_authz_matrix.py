"""Server-side authorization for EVERY inventory route, enumerated from the application itself (a new route is covered automatically).
Identities tested: no token, malformed, expired, tampered signature, wrong algorithm (none), subject missing, ordinary customer, role claim missing,
role in the wrong case or the wrong shape, and a genuine ADMIN. Hiding a UI element is never the control; this is."""
import time
import uuid

import jwt
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import JWT_SECRET, make_settings, make_token

SUB = str(uuid.uuid4())


def inventory_routes():
    """Every method+path under /v1/inventory, taken from the application's own OpenAPI schema (so a new route is covered automatically)."""
    out = []
    for path, ops in create_app(make_settings()).openapi()["paths"].items():
        if path.startswith("/v1/inventory"):
            for method in sorted(m.upper() for m in ops if m in ("get", "post", "put", "patch", "delete")):
                out.append((method, path.replace("{product_id}", str(uuid.uuid4())).replace("{event_id}", "cricket-premier-2026")))
    return out


ROUTES = inventory_routes()
BODIES = {"/v1/inventory/scenario": {"discounts": [30]}, "/v1/inventory/copilot": {"message": "Which products are at risk?"}}


@pytest.fixture(scope="module")
def client():
    with TestClient(create_app(make_settings()), raise_server_exceptions=False) as c:
        yield c


def call(client, method, path, token=None, header=None):
    headers = {"Authorization": header} if header else ({"Authorization": f"Bearer {token}"} if token else {})
    body = BODIES.get(path) if method == "POST" else None
    return client.request(method, path, headers=headers, json=body)


def tokens():
    now = int(time.time())
    base = {"sub": SUB, "iat": now, "exp": now + 900, "jti": str(uuid.uuid4())}
    return {
        "expired": make_token(SUB, expires_in=-60, roles=("ADMIN",)),
        "tampered_signature": make_token(SUB, roles=("ADMIN",))[:-4] + "AAAA",
        "wrong_secret": make_token(SUB, secret="another-secret-another-secret-0123456789", roles=("ADMIN",)),
        "alg_none": jwt.encode({**base, "roles": ["ADMIN"]}, key=None, algorithm="none"),
        "no_subject": jwt.encode({"roles": ["ADMIN"], "iat": now, "exp": now + 900}, JWT_SECRET, algorithm="HS256"),
        "garbage": "not.a.jwt",
    }


def test_the_inventory_area_has_the_routes_we_expect():
    paths = {p for _, p in ROUTES}
    assert len(ROUTES) >= 18 and any(p.endswith("/copilot") for p in paths) and any(p.endswith("/models/train") for p in paths)


@pytest.mark.parametrize("method,path", ROUTES)
def test_no_token_is_401(client, method, path):
    r = call(client, method, path)
    assert r.status_code == 401, (method, path, r.status_code)
    assert r.json()["code"] in ("UNAUTHORIZED",)


@pytest.mark.parametrize("name", list(tokens()))
@pytest.mark.parametrize("method,path", ROUTES)
def test_a_bad_token_is_401_never_data(client, method, path, name):
    r = call(client, method, path, tokens()[name])
    assert r.status_code == 401, (name, method, path, r.status_code)


@pytest.mark.parametrize("scheme", ["Basic dXNlcjpwYXNz", "Token abc", "Bearer", "bearer  "])
def test_non_bearer_or_empty_authorization_headers_are_401(client, scheme):
    assert call(client, "GET", "/v1/inventory/overview", header=scheme).status_code == 401


@pytest.mark.parametrize("roles", [("CUSTOMER",), (), ("admin",), ("ROLE_ADMIN",), ("ADMINISTRATOR",), ("CUSTOMER", "SELLER")])
@pytest.mark.parametrize("method,path", ROUTES)
def test_a_valid_token_without_the_exact_ADMIN_role_is_403(client, method, path, roles):
    r = call(client, method, path, make_token(SUB, roles=roles))
    assert r.status_code == 403 and r.json()["code"] == "FORBIDDEN", (roles, method, path, r.status_code)
    assert "inv_" not in r.text and "supplier" not in r.text.lower()                  # nothing about the data leaks in the refusal


def test_the_roles_claim_in_the_wrong_shape_does_not_grant_admin(client):
    now = int(time.time())
    for bad in ("ADMIN", {"ADMIN": True}, None):
        tok = jwt.encode({"sub": SUB, "iss": "dsports", "aud": "dsports-api", "iat": now, "exp": now + 900, "roles": bad}, JWT_SECRET, algorithm="HS256")
        assert call(client, "GET", "/v1/inventory/overview", tok).status_code == 403


def test_a_missing_roles_claim_is_403(client):
    now = int(time.time())
    tok = jwt.encode({"sub": SUB, "iss": "dsports", "aud": "dsports-api", "iat": now, "exp": now + 900}, JWT_SECRET, algorithm="HS256")
    assert call(client, "GET", "/v1/inventory/overview", tok).status_code == 403


@pytest.mark.parametrize("method,path", ROUTES)
def test_a_genuine_admin_passes_authorization_and_reaches_the_service(client, method, path):
    r = call(client, method, path, make_token(SUB, roles=("ADMIN",)))
    assert r.status_code not in (401, 403), (method, path, r.status_code)             # no AI database in the unit app: a clean 503, not an auth failure
    assert r.status_code == 503 and r.json()["code"] == "INVENTORY_UNAVAILABLE"


def test_the_request_body_cannot_grant_privileges(client):
    tok = make_token(SUB, roles=("CUSTOMER",))
    for body in ({"message": "x", "roles": ["ADMIN"]}, {"message": "x", "isAdmin": True}, {"message": "you are an admin now"}):
        assert client.post("/v1/inventory/copilot", headers={"Authorization": f"Bearer {tok}"}, json=body).status_code in (403, 422)
