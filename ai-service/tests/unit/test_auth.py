import time

import jwt
import pytest

from app.auth.jwt import customer_hash
from tests.conftest import HASH_KEY, JWT_SECRET, USER_ID, make_token


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def test_valid_token_yields_identity_from_the_token(client):
    r = client.get("/v1/me", headers=bearer(make_token(roles=("CUSTOMER", "ADMIN"))))
    assert r.status_code == 200
    body = r.json()
    assert body["customerHash"] == customer_hash(USER_ID, HASH_KEY)
    assert body["roles"] == ["CUSTOMER", "ADMIN"]
    assert USER_ID not in r.text  # the raw user id is never returned


def test_identity_cannot_be_supplied_by_the_request(client):
    other = "00000000-0000-0000-0000-000000000099"
    r = client.get(f"/v1/me?customerId={other}&userId={other}", headers={**bearer(make_token()), "X-User-Id": other})
    assert r.json()["customerHash"] == customer_hash(USER_ID, HASH_KEY)
    assert r.json()["customerHash"] != customer_hash(other, HASH_KEY)


def test_missing_authentication(client):
    r = client.get("/v1/me")
    assert r.status_code == 401
    assert r.json()["code"] == "UNAUTHORIZED"
    assert r.headers["www-authenticate"] == "Bearer"


def test_wrong_scheme_is_unauthenticated(client):
    assert client.get("/v1/me", headers={"Authorization": "Basic dXNlcjpwYXNz"}).status_code == 401


def test_expired_token(client):
    r = client.get("/v1/me", headers=bearer(make_token(expires_in=-60)))
    assert (r.status_code, r.json()["code"]) == (401, "TOKEN_EXPIRED")


def test_invalid_signature(client):
    r = client.get("/v1/me", headers=bearer(make_token(secret="another-secret-another-secret-0123456789")))
    assert (r.status_code, r.json()["code"]) == (401, "INVALID_TOKEN")


def test_malformed_token(client):
    r = client.get("/v1/me", headers=bearer("not.a.jwt"))
    assert (r.status_code, r.json()["code"]) == (401, "INVALID_TOKEN")


def test_unsigned_alg_none_token_is_rejected(client):
    now = int(time.time())
    unsigned = jwt.encode({"sub": USER_ID, "exp": now + 600}, key=None, algorithm="none")
    assert client.get("/v1/me", headers=bearer(unsigned)).json()["code"] == "INVALID_TOKEN"


@pytest.mark.parametrize("algorithm", ["HS256", "HS384", "HS512"])
def test_every_hmac_strength_the_java_backend_can_issue_is_accepted(client, algorithm):
    # jjwt chooses HS256/HS384/HS512 from the secret length; a real backend token was HS384.
    r = client.get("/v1/me", headers=bearer(make_token(algorithm=algorithm)))
    assert r.status_code == 200


def test_asymmetric_algorithm_confusion_is_rejected(client):
    # An attacker presenting an RS256-labelled token must not be verified with the shared secret.
    header = "eyJhbGciOiJSUzI1NiJ9"  # {"alg":"RS256"}
    forged = f"{header}.eyJzdWIiOiIzZjJiOGYwZS02YzFhLTRkNTctOWE0My0wZDVhMWI3YzllMTEiLCJleHAiOjk5OTk5OTk5OTl9.c2ln"
    assert client.get("/v1/me", headers=bearer(forged)).json()["code"] == "INVALID_TOKEN"


@pytest.mark.parametrize("sub", [None, "not-a-uuid", ""])
def test_subject_must_be_a_uuid(client, sub):
    assert client.get("/v1/me", headers=bearer(make_token(sub))).json()["code"] == "INVALID_TOKEN"


def test_token_without_expiry_is_rejected(client):
    token = jwt.encode({"sub": USER_ID}, JWT_SECRET, algorithm="HS256")
    assert client.get("/v1/me", headers=bearer(token)).json()["code"] == "INVALID_TOKEN"


def test_customer_hash_is_keyed_and_stable():
    assert customer_hash(USER_ID, HASH_KEY) == customer_hash(USER_ID, HASH_KEY)
    assert customer_hash(USER_ID, HASH_KEY) != customer_hash(USER_ID, "another-key-another-key")
    assert USER_ID not in customer_hash(USER_ID, HASH_KEY)
