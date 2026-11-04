"""Every operation of the AI service, enumerated from the application's own OpenAPI document (a new route is covered automatically), against every kind of caller.

A new route that is reachable by someone it should not be makes this test fail, so exposing a route is always a conscious decision recorded here. The UI hiding a screen is never the control."""
from __future__ import annotations

import re
import time
import uuid

import jwt
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import JWT_SECRET, make_settings, make_token

SUB = str(uuid.uuid4())

# Reachable by an anonymous caller on purpose: liveness, a public product-recommendation list and the guest shopping assistant.
ANONYMOUS_OK = {("GET", "/health"), ("GET", "/health/dependencies"), ("GET", "/v1/recommendations"), ("POST", "/v1/chat")}
# Reachable by a signed-in customer on purpose (their own account, conversations, cases and recommendations); the server scopes each to the caller.
CUSTOMER_OK = ANONYMOUS_OK | {("GET", "/v1/me"), ("POST", "/v1/confirm"), ("GET", "/v1/offers/recommend"), ("GET", "/v1/support/conversation"), ("POST", "/v1/support/feedback")}

# prefix -> the roles that may use it
AREAS = {
    "/v1/inventory": {"ADMIN"},
    "/v1/engineering": {"ADMIN"},
    "/v1/planning": {"ADMIN", "CATEGORY_MANAGER", "SALES_PLANNER", "REGIONAL_MANAGER", "STORE_MANAGER"},
    "/v1/sourcing": {"ADMIN", "CATEGORY_MANAGER", "PROCUREMENT_BUYER"},
    "/v1/support/staff": {"ADMIN", "SUPPORT_EXECUTIVE"},
}
PERSONAS = ["CUSTOMER", "SUPPORT_EXECUTIVE", "SALES_PLANNER", "STORE_MANAGER", "REGIONAL_MANAGER", "PROCUREMENT_BUYER", "CATEGORY_MANAGER", "ADMIN"]


def operations():
    out = []
    for path, ops in create_app(make_settings()).openapi()["paths"].items():
        for method in ops:
            if method in ("get", "post", "put", "patch", "delete"):
                out.append((method.upper(), path))
    return sorted(out)


OPS = operations()


def concrete(path: str) -> str:
    return re.sub(r"\{[^}]+\}", lambda _m: str(uuid.uuid4()), path)


@pytest.fixture(scope="module")
def client():
    with TestClient(create_app(make_settings()), raise_server_exceptions=False) as c:
        yield c


def call(client, method, path, token=None, header=None):
    headers = {"Authorization": header} if header else ({"Authorization": f"Bearer {token}"} if token else {})
    return client.request(method, concrete(path), headers=headers, json={} if method != "GET" else None)


def area_of(path: str):
    return next((p for p in sorted(AREAS, key=len, reverse=True) if path.startswith(p)), None)


def test_the_application_has_the_operations_we_expect():
    assert len(OPS) > 100 and ("POST", "/v1/planning/review/bulk") in OPS and ("GET", "/v1/me") in OPS


def test_only_the_listed_operations_answer_an_anonymous_caller(client):
    open_ops = {(m, p) for m, p in OPS if call(client, m, p).status_code not in (401, 403)}
    assert open_ops == ANONYMOUS_OK, sorted(open_ops ^ ANONYMOUS_OK)


def test_an_ordinary_customer_reaches_only_customer_operations_and_every_internal_area_is_403(client):
    token = make_token(SUB, roles=("CUSTOMER",))
    reachable = {(m, p) for m, p in OPS if call(client, m, p, token).status_code != 403}
    assert reachable == CUSTOMER_OK, sorted(reachable ^ CUSTOMER_OK)
    internal = [(m, p) for m, p in OPS if area_of(p)]
    assert len(internal) > 90
    assert all(call(client, m, p, token).status_code == 403 for m, p in internal)


@pytest.mark.parametrize("persona", PERSONAS)
def test_each_role_reaches_exactly_its_own_areas(client, persona):
    token = make_token(SUB, roles=("CUSTOMER", persona) if persona != "CUSTOMER" else ("CUSTOMER",))
    for method, path in OPS:
        area = area_of(path)
        if area is None:
            continue
        status = call(client, method, path, token).status_code
        allowed = persona in AREAS[area]
        assert (status not in (401, 403)) == allowed, (persona, method, path, status)
        assert status < 500 or status in (502, 503, 504), (persona, method, path, status)      # a role may be refused or unserved (no database, DSports unreachable here); it must never crash (500)


def test_a_role_name_in_the_wrong_case_or_shape_grants_nothing(client):
    now = int(time.time())
    for roles in (["admin"], ["ROLE_ADMIN "], "ADMIN", {"ADMIN": True}, [["ADMIN"]], [None], ["ADMIN\n"], []):
        tok = jwt.encode({"sub": SUB, "iss": "dsports", "aud": "dsports-api", "iat": now, "exp": now + 900, "roles": roles}, JWT_SECRET, algorithm="HS256")
        for path in ("/v1/inventory/overview", "/v1/engineering/sources", "/v1/planning/readiness", "/v1/sourcing/dashboard", "/v1/support/staff/insights"):
            assert client.get(path, headers={"Authorization": f"Bearer {tok}"}).status_code in (401, 403), (roles, path)


@pytest.mark.parametrize("name", ["expired", "tampered", "wrong_secret", "alg_none", "no_subject", "wrong_audience", "wrong_issuer", "no_issuer_or_audience", "garbage", "empty", "basic"])
def test_bad_credentials_are_401_on_every_kind_of_route(client, name):
    now = int(time.time())
    good = {"sub": SUB, "iss": "dsports", "aud": "dsports-api", "iat": now, "exp": now + 900, "roles": ["ADMIN"]}
    headers = {
        "expired": lambda: "Bearer " + jwt.encode({**good, "exp": now - 60}, JWT_SECRET, algorithm="HS256"),
        "tampered": lambda: "Bearer " + jwt.encode(good, JWT_SECRET, algorithm="HS256")[:-4] + "AAAA",
        "wrong_secret": lambda: "Bearer " + jwt.encode(good, "another-secret-another-secret-0123456789", algorithm="HS256"),
        "alg_none": lambda: "Bearer " + jwt.encode(good, key=None, algorithm="none"),
        "no_subject": lambda: "Bearer " + jwt.encode({k: v for k, v in good.items() if k != "sub"}, JWT_SECRET, algorithm="HS256"),
        "wrong_audience": lambda: "Bearer " + jwt.encode({**good, "aud": "another-service"}, JWT_SECRET, algorithm="HS256"),
        "wrong_issuer": lambda: "Bearer " + jwt.encode({**good, "iss": "someone-else"}, JWT_SECRET, algorithm="HS256"),
        "no_issuer_or_audience": lambda: "Bearer " + jwt.encode({k: v for k, v in good.items() if k not in ("iss", "aud")}, JWT_SECRET, algorithm="HS256"),
        "garbage": lambda: "Bearer not.a.jwt",
        "empty": lambda: "Bearer ",
        "basic": lambda: "Basic YWRtaW46YWRtaW4=",
    }[name]()
    for path in ("/v1/me", "/v1/inventory/overview", "/v1/planning/readiness", "/v1/sourcing/dashboard", "/v1/support/staff/insights", "/v1/engineering/sources"):
        r = client.get(path, headers={"Authorization": headers})
        assert r.status_code == 401, (name, path, r.status_code)
        body = r.json()
        assert set(body) >= {"code", "message"} and "Traceback" not in r.text and JWT_SECRET not in r.text
    r = client.post("/v1/chat", headers={"Authorization": headers}, json={"message": "hi"})
    assert r.status_code == 401                                                  # a present but invalid token is never a silent downgrade to guest


def test_forged_identity_headers_and_body_fields_are_ignored(client):
    token = make_token(SUB, roles=("CUSTOMER",))
    for extra in ({"X-User-Id": str(uuid.uuid4())}, {"X-Roles": "ADMIN"}, {"X-Customer-Id": str(uuid.uuid4())}, {"X-Forwarded-User": "admin"}):
        assert client.get("/v1/inventory/overview", headers={"Authorization": f"Bearer {token}", **extra}).status_code == 403
        me = client.get("/v1/me", headers={"Authorization": f"Bearer {token}", **extra}).json()
        assert "ADMIN" not in str(me)
    body = {"userId": str(uuid.uuid4()), "roles": ["ADMIN"], "customerId": str(uuid.uuid4())}
    assert client.post("/v1/planning/plans", headers={"Authorization": f"Bearer {token}"}, json=body).status_code == 403


def test_refusals_do_not_leak_internals(client):
    token = make_token(SUB, roles=("CUSTOMER",))
    for path in ("/v1/inventory/overview", "/v1/planning/plans", "/v1/sourcing/suppliers", "/v1/engineering/incidents"):
        r = client.get(path, headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 403
        text = r.text.lower()
        assert not any(w in text for w in ("traceback", "postgres", "asyncpg", "inv_", "pln_", "src_", ".py"))
