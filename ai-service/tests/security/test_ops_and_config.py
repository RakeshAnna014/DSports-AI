"""Operational surfaces and configuration: documentation, metrics and dependency health are not public in production; secrets are mandatory, strong and never echoed; unexpected errors reveal nothing."""
from __future__ import annotations

import uuid

import jwt
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config.settings import Settings
from app.main import create_app
from tests.conftest import JWT_SECRET, make_settings, make_token

SUB = str(uuid.uuid4())
ADMIN = make_token(SUB, roles=("CUSTOMER", "ADMIN"))
CUSTOMER = make_token(str(uuid.uuid4()), roles=("CUSTOMER",))


def client_for(**overrides):
    return TestClient(create_app(make_settings(**overrides)), raise_server_exceptions=False)


def test_the_api_documentation_is_served_in_development_and_not_in_production():
    with client_for(AI_ENV="local") as c:
        assert c.get("/openapi.json").status_code == 200 and c.get("/docs").status_code == 200
    for env in ("prod", "production", "PROD"):
        with client_for(AI_ENV=env) as c:
            assert c.get("/openapi.json").status_code == 404 and c.get("/docs").status_code == 404 and c.get("/redoc").status_code == 404


def test_metrics_are_open_only_in_local_development():
    with client_for(AI_ENV="local") as c:
        assert c.get("/metrics").status_code == 200
    with client_for(AI_ENV="prod", AI_METRICS_TOKEN="scrape-token-scrape-token-0123456789") as c:
        assert c.get("/metrics").status_code == 401
        assert c.get("/metrics", headers={"Authorization": f"Bearer {CUSTOMER}"}).status_code == 403
        assert c.get("/metrics", headers={"Authorization": f"Bearer {ADMIN}"}).status_code == 200
        assert c.get("/metrics", headers={"Authorization": "Bearer scrape-token-scrape-token-0123456789"}).status_code == 200
        assert c.get("/metrics", headers={"Authorization": "Bearer a-wrong-scrape-token"}).status_code in (401, 403)
        assert c.get("/metrics", headers={"Authorization": "Basic YWRtaW46YWRtaW4="}).status_code == 401
    with client_for(AI_ENV="prod") as c:                                                # no scrape token configured: only an administrator
        assert c.get("/metrics", headers={"Authorization": "Bearer anything"}).status_code in (401, 403)
        assert c.get("/metrics", headers={"Authorization": f"Bearer {ADMIN}"}).status_code == 200


def test_dependency_health_shows_configuration_to_administrators_only_and_never_a_secret():
    with client_for(AI_ENV="prod", AI_DATABASE_URL="postgresql://dsports:hunter2-hunter2@db.internal:5432/dsports_ai") as c:
        anonymous = c.get("/health/dependencies")
        as_customer = c.get("/health/dependencies", headers={"Authorization": f"Bearer {CUSTOMER}"})
        as_admin = c.get("/health/dependencies", headers={"Authorization": f"Bearer {ADMIN}"})
        assert anonymous.status_code == as_customer.status_code == as_admin.status_code == 200
        assert "config" not in anonymous.json() and "config" not in as_customer.json() and "config" in as_admin.json()
        for r in (anonymous, as_customer, as_admin):
            assert JWT_SECRET not in r.text and "hunter2" not in r.text and "db.internal" not in r.text
        assert c.get("/health/dependencies", headers={"Authorization": "Bearer not.a.jwt"}).status_code == 401      # present but invalid: never a silent downgrade


def test_startup_refuses_a_missing_or_weak_secret():
    base = dict(JWT_SECRET=JWT_SECRET, AI_CUSTOMER_HASH_KEY="hash-key-hash-key-0123456789", DSPORTS_BASE_URL="http://127.0.0.1:9")
    assert Settings(_env_file=None, **base)
    for bad in ({"JWT_SECRET": "short"}, {"JWT_SECRET": ""}, {"AI_CUSTOMER_HASH_KEY": "tiny"}, {"AI_CUSTOMER_HASH_KEY": ""}):
        with pytest.raises(ValidationError):
            Settings(_env_file=None, **{**base, **bad})
    missing = {k: v for k, v in base.items() if k != "JWT_SECRET"}
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **missing)


def test_secrets_are_not_in_the_repr_the_public_view_or_the_startup_log_line():
    s = make_settings(AI_METRICS_TOKEN="scrape-token-scrape-token-0123456789", AI_DATABASE_URL="postgresql://dsports:hunter2-hunter2@db.internal:5432/dsports_ai")
    for text in (repr(s), str(s.public_view()), str(s)):
        for secret in (JWT_SECRET, "scrape-token-scrape-token-0123456789", "hunter2-hunter2"):
            assert secret not in text


def test_an_unexpected_error_is_a_generic_500_with_no_stack_trace_path_or_message():
    app = create_app(make_settings())

    @app.get("/boom-test", include_in_schema=False)
    async def boom():
        raise RuntimeError("connect to postgres://dsports:hunter2@10.0.0.9/db failed at C:\\secret\\path\\x.py")
    with TestClient(app, raise_server_exceptions=False) as c:
        r = c.get("/boom-test")
    assert r.status_code == 500 and r.json()["code"] == "INTERNAL_ERROR"
    for leak in ("hunter2", "10.0.0.9", "secret", "Traceback", ".py", "postgres"):
        assert leak not in r.text


def test_oversized_and_malformed_bodies_are_refused_without_a_crash():
    token = make_token(SUB, roles=("CUSTOMER",))
    with client_for() as c:
        h = {"Authorization": f"Bearer {token}"}
        assert c.post("/v1/chat", headers=h, json={"message": "x" * 200_000}).status_code in (400, 413, 422)
        assert c.post("/v1/chat", headers={**h, "Content-Type": "application/json"}, content=b"{not json").status_code == 422
        assert c.post("/v1/chat", headers=h, json=["not", "an", "object"]).status_code == 422
        assert c.post("/v1/chat", headers=h, json={"message": {"nested": {"a": [1] * 1000}}}).status_code == 422
        assert c.post("/v1/chat", headers=h, json={"message": None}).status_code == 422


def test_the_access_token_is_never_echoed_by_the_identity_endpoint():
    with client_for() as c:
        r = c.get("/v1/me", headers={"Authorization": f"Bearer {CUSTOMER}"})
    assert r.status_code == 200 and CUSTOMER not in r.text and jwt.decode(CUSTOMER, options={"verify_signature": False})["sub"] not in r.text.replace('"sub"', "")


def test_a_value_the_database_cannot_represent_is_a_422_without_the_database_message_and_other_database_errors_are_a_generic_500():
    import asyncpg
    app = create_app(make_settings())

    @app.get("/bad-uuid-test", include_in_schema=False)
    async def bad_uuid():
        raise asyncpg.exceptions.InvalidTextRepresentationError("invalid input syntax for type uuid: \"' OR 1=1--\" in table pln_plan column id")

    @app.get("/bad-arg-test", include_in_schema=False)
    async def bad_arg():
        raise asyncpg.exceptions.DataError("invalid input for query argument $4: '2026-10-05' ('str' object has no attribute 'toordinal')")

    @app.get("/db-down-test", include_in_schema=False)
    async def db_down():
        raise asyncpg.exceptions.UndefinedTableError('relation "pln_plan" does not exist')
    with TestClient(app, raise_server_exceptions=False) as c:
        for path in ("/bad-uuid-test", "/bad-arg-test"):
            r = c.get(path)
            assert r.status_code == 422 and r.json()["code"] == "INVALID_REQUEST"
            assert not any(w in r.text for w in ("pln_plan", "syntax", "toordinal", "OR 1=1", "invalid input"))
        r = c.get("/db-down-test")
        assert r.status_code == 500 and r.json()["code"] == "INTERNAL_ERROR" and "pln_plan" not in r.text

@pytest.mark.parametrize("bad", ["javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "ftp://example.com/x", "//evil.example.net/x", "vbscript:x", "JAVASCRIPT:alert(1)"])
def test_links_stored_for_buyers_must_be_http_or_https(bad):
    """A product page or a quotation source is shown to other buyers as a link; the server refuses anything that is not a web address, whoever sends it."""
    from app.sourcing import api as sourcing_api
    models = [m for m in vars(sourcing_api).values() if isinstance(m, type) and hasattr(m, "model_fields") and any(f in m.model_fields for f in ("product_url", "source_url"))]
    assert {m.__name__ for m in models} >= {"OpportunityCreate"}
    for model in models:
        field = "product_url" if "product_url" in model.model_fields else "source_url"
        with pytest.raises(ValidationError) as e:
            model.model_validate({field: bad})
        assert any(field in err["loc"] for err in e.value.errors()), (model.__name__, e.value.errors())
    sourcing_api.OpportunityCreate.model_validate({"name": "Bat", "product_url": "https://www.victorsport.com/p/1"})        # a genuine address is accepted
