import json

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from tests.conftest import make_settings, make_token, token_for
from tests.fakes import BAT_MRF, DEFAULT_CHUNKS, SHOES, USER_A, USER_B, FakeDSports, FakeLLM, FakeRetriever


def make_client(**settings_overrides):
    dsports = FakeDSports()
    for user in (USER_A, USER_B):
        dsports.customer(user, token_for(user))
    dsports.add_order(USER_A, "ORD-20261001-000001", "DELIVERED", [(SHOES, 1)], shipment_status="DELIVERED")
    llm = FakeLLM(default="The **MRF Cricket Bat - Virgin** at ₹6,499 is a good starting point.")
    retriever = FakeRetriever(dsports.products, [dict(c) for c in DEFAULT_CHUNKS])
    app = create_app(make_settings(**settings_overrides), http=dsports.client(), llm_inner=llm, retriever=retriever)
    return app, dsports, llm


@pytest.fixture
def api():
    app, dsports, llm = make_client()
    with TestClient(app, raise_server_exceptions=False) as client:
        client.dsports, client.llm = dsports, llm
        yield client


def auth(user=USER_A):
    return {"Authorization": f"Bearer {token_for(user)}"}


def test_guest_chat_works_and_returns_the_documented_shape(api):
    r = api.post("/v1/chat", json={"message": "show me cricket bats under 8000"})
    body = r.json()
    assert r.status_code == 200 and body["status"] == "ok" and body["conversationId"]
    assert {"intent", "message", "products", "comparison", "orders", "actions", "sources", "requiresConfirmation", "confirmationToken",
            "disclaimers", "meta"} <= set(body)
    assert body["products"][0]["name"] == "MRF Cricket Bat - Virgin" and body["meta"]["correlationId"] == r.headers["x-correlation-id"]


def test_authenticated_chat_uses_the_token_identity(api):
    r = api.post("/v1/chat", json={"message": "show my orders"}, headers=auth())
    assert [o["order_number"] for o in r.json()["orders"]] == ["ORD-20261001-000001"]


def test_a_customer_id_in_the_body_is_ignored(api):
    r = api.post("/v1/chat", json={"message": "show my orders", "customerId": USER_A, "userId": USER_A}, headers=auth(USER_B))
    assert r.status_code == 200 and r.json()["orders"] == []      # Bob sees Bob's (empty) orders, whatever the body says


def test_every_document_of_another_customers_order_is_unreachable_through_chat(api):
    r = api.post("/v1/chat", json={"message": "where is order ORD-20261001-000001?"}, headers=auth(USER_B))
    body = r.json()
    assert body["tracking"] is None and "ORD-20261001-000001" not in json.dumps(body["orders"])
    assert "TRK-" not in body["message"] and "DSC Cricket Shoes" not in json.dumps(body)


@pytest.mark.parametrize("headers", [{"Authorization": "Bearer not.a.jwt"}, {"Authorization": "Bearer " + make_token(expires_in=-5)},
                                     {"Authorization": "Bearer " + make_token(secret="x" * 40)}, {"Authorization": "Basic abc"}])
def test_a_bad_token_is_a_401_never_a_silent_guest(api, headers):
    r = api.post("/v1/chat", json={"message": "show my orders"}, headers=headers)
    assert r.status_code == 401 and r.json()["code"] in ("INVALID_TOKEN", "TOKEN_EXPIRED", "UNAUTHORIZED")


def test_request_validation(api):
    assert api.post("/v1/chat", json={"message": ""}).status_code == 422
    assert api.post("/v1/chat", json={}).status_code == 422
    r = api.post("/v1/chat", json={"message": "x" * 4001})
    assert r.status_code == 422 and "x" * 50 not in r.text                       # input values are never echoed back
    r = api.post("/v1/chat", json={"message": "x" * 1500})
    assert r.status_code == 200 and "too long" in r.json()["message"].lower()


def test_correlation_id_is_propagated_to_dsports_and_the_response(api):
    r = api.post("/v1/chat", json={"message": "show my orders"}, headers={**auth(), "X-Correlation-Id": "e2e-api-1"})
    assert r.headers["x-correlation-id"] == "e2e-api-1" and r.json()["meta"]["correlationId"] == "e2e-api-1"
    headers = next(h for m, p, h in api.dsports.requests if p == "/api/v1/orders")
    assert headers["x-correlation-id"] == "e2e-api-1"


def test_confirm_endpoint_needs_authentication(api):
    assert api.post("/v1/confirm", json={"confirmationToken": "v1.abc.def"}).status_code == 401


def test_confirm_flow_over_http_is_single_use(api):
    c = api.post("/v1/chat", json={"message": "Can I return the shoes I bought?"}, headers=auth()).json()["conversationId"]
    asked = api.post("/v1/chat", json={"message": "Yes, return them.", "conversationId": c}, headers=auth()).json()
    assert asked["requiresConfirmation"] and asked["actions"][0]["tool"] == "create_return"
    token = asked["confirmationToken"]
    done = api.post("/v1/confirm", json={"confirmationToken": token, "conversationId": c}, headers=auth()).json()
    assert done["actionResult"]["status"] == "REQUESTED" and len(api.dsports.returns) == 1
    replay = api.post("/v1/confirm", json={"confirmationToken": token}, headers=auth()).json()
    assert replay["errorCode"] == "CONFIRMATION_ALREADY_USED" and len(api.dsports.returns) == 1


def test_someone_elses_confirmation_token_is_useless(api):
    c = api.post("/v1/chat", json={"message": "Can I return the shoes I bought?"}, headers=auth()).json()["conversationId"]
    token = api.post("/v1/chat", json={"message": "Yes, return them.", "conversationId": c}, headers=auth()).json()["confirmationToken"]
    stolen = api.post("/v1/confirm", json={"confirmationToken": token}, headers=auth(USER_B)).json()
    assert stolen["errorCode"] == "CONFIRMATION_INVALID" and api.dsports.returns == []


def test_rate_limit_returns_429_with_the_error_contract():
    app, *_ = make_client(AI_RATE_LIMIT_PER_MIN=2)
    with TestClient(app) as c:
        codes = [c.post("/v1/chat", json={"message": "hello"}, headers=auth()).status_code for _ in range(3)]
        assert codes == [200, 200, 429]
        assert c.post("/v1/chat", json={"message": "hello"}, headers=auth()).json()["code"] == "RATE_LIMITED"


def test_metrics_are_exposed_without_personal_data(api):
    api.post("/v1/chat", json={"message": "show my orders", "conversationId": None}, headers=auth())
    api.post("/v1/chat", json={"message": "show me cricket bats"})
    text = api.get("/metrics").text
    for metric in ("ai_requests_total", "ai_tool_latency_seconds", "ai_llm_tokens_total", "ai_rag_hit_ratio", "ai_cost_usd_total", "ai_guard_blocks_total"):
        assert metric in text, metric
    assert 'intent="ORDERS_LIST"' in text and 'tool="get_orders"' in text
    for private in (USER_A, "ORD-2026", "asha@example.com", "Bearer "):
        assert private not in text


def test_dependency_health_reports_breaker_and_never_calls_the_model(api):
    before = len(api.llm.prompts)
    body = api.get("/health/dependencies").json()
    assert body["dependencies"]["llm"]["circuit_breaker"] == "closed" and len(api.llm.prompts) == before


def test_when_the_model_is_down_the_storefront_flows_still_answer():
    app, dsports, llm = make_client()
    llm.default = lambda *_: (_ for _ in ()).throw(RuntimeError)
    from app.llm.base import LLMError
    llm.script = [LLMError("LLM_ERROR", "down")] * 40
    with TestClient(app) as c:
        assert c.post("/v1/chat", json={"message": "show my orders"}, headers=auth()).json()["status"] == "ok"
        r = c.post("/v1/chat", json={"message": "hmm what do you think about all of this"}).json()
        assert r["status"] == "ai_unavailable" and r["errorCode"] == "AI_UNAVAILABLE" and "temporarily unavailable" in r["message"]
        assert c.get("/health").json()["status"] == "UP"
