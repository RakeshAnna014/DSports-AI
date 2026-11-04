import json
import logging
import uuid

import pytest

from app.obs.context import resolve_correlation_id
from app.obs.logging import REDACTED, redact, redact_text
from app.obs.usage import UsageRecorder, estimate_cost
from tests.conftest import JWT_SECRET, USER_ID, make_token


def log_lines(capsys) -> list[dict]:
    out = capsys.readouterr().out
    return [json.loads(line) for line in out.splitlines() if line.startswith("{")]


# ---------------------------------------------------------------- correlation id

def test_correlation_id_is_generated_when_absent(client):
    r = client.get("/health")
    assert str(uuid.UUID(r.headers["x-correlation-id"]))
    assert str(uuid.UUID(r.headers["x-request-id"]))


def test_supplied_correlation_id_is_propagated(client):
    r = client.get("/health", headers={"X-Correlation-Id": "e2e-123.abc_X"})
    assert r.headers["x-correlation-id"] == "e2e-123.abc_X"


def test_correlation_id_appears_in_error_bodies(client):
    r = client.get("/v1/me", headers={"X-Correlation-Id": "trace-42"})
    assert r.json()["correlationId"] == "trace-42"


@pytest.mark.parametrize("unsafe", ["bad value!", "x" * 65, "line\\nbreak", "<script>", ""])
def test_unsafe_correlation_id_is_replaced(unsafe):
    resolved = resolve_correlation_id(unsafe)
    assert resolved != unsafe
    assert str(uuid.UUID(resolved))


def test_each_request_gets_its_own_request_id(client):
    ids = {client.get("/health").headers["x-request-id"] for _ in range(3)}
    assert len(ids) == 3


def test_request_log_carries_correlation_and_request_ids(client, capsys):
    capsys.readouterr()
    r = client.get("/health", headers={"X-Correlation-Id": "trace-log-1"})
    line = next(l for l in log_lines(capsys) if l["message"] == "request completed")
    assert line["correlationId"] == "trace-log-1"
    assert line["requestId"] == r.headers["x-request-id"]
    assert (line["method"], line["path"], line["status"]) == ("GET", "/health", 200)


# ---------------------------------------------------------------- redaction

def test_redact_text_removes_credentials_and_personal_data():
    token = make_token()
    cases = [
        f"Authorization: Bearer {token}", token, "contact me at jane.doe@example.com",
        "dsn=postgresql://user:hunter2@db:5432/dsports_ai", f"customer {USER_ID} asked",
        "-----BEGIN PRIVATE KEY-----\nabc123\n-----END PRIVATE KEY-----",
    ]
    for text in cases:
        cleaned = redact_text(text)
        assert REDACTED in cleaned
        for leaked in (token, "jane.doe", "hunter2", USER_ID, "abc123"):
            assert leaked not in cleaned


def test_redact_hides_sensitive_keys_recursively():
    cleaned = redact({"Authorization": "Bearer x", "nested": {"password": "p", "phone": "9999999999",
                      "address": "1 MG Road", "email": "a@b.co", "user_id": USER_ID, "ok": "visible"},
                      "tokens_in": 10})
    assert cleaned["Authorization"] == REDACTED
    assert cleaned["nested"] == {"password": REDACTED, "phone": REDACTED, "address": REDACTED, "email": REDACTED,
                                 "user_id": REDACTED, "ok": "visible"}
    assert cleaned["tokens_in"] == 10  # token *counts* are not secrets


def test_secrets_never_reach_the_log_output(client, capsys):
    capsys.readouterr()
    token = make_token()
    client.get("/v1/me?email=jane@example.com", headers={"Authorization": f"Bearer {token}"})
    log = logging.getLogger("test")
    log.warning("login for jane@example.com with Bearer %s", token,
                extra={"authorization": f"Bearer {token}", "password": "hunter2", "phone": "9876543210"})
    out = capsys.readouterr().out
    for leaked in (token, JWT_SECRET, "jane@example.com", "hunter2", "9876543210", USER_ID):
        assert leaked not in out
    assert REDACTED in out


def test_authenticated_request_logs_hash_not_raw_customer_id(client, capsys):
    capsys.readouterr()
    client.get("/v1/me", headers={"Authorization": f"Bearer {make_token()}"})
    line = next(l for l in log_lines(capsys) if l["message"] == "request completed")
    assert len(line["customer_hash"]) == 32
    assert USER_ID not in json.dumps(line)


# ---------------------------------------------------------------- usage / cost

PRICES = {"m": {"input_per_1m": 0.30, "output_per_1m": 2.50}}


def test_cost_is_computed_only_from_the_supplied_price_table():
    assert estimate_cost("m", 1_000_000, 1_000_000, PRICES) == pytest.approx(2.80)
    assert estimate_cost("m", 2000, 500, PRICES) == pytest.approx(0.00185)
    assert estimate_cost("unpriced", 1000, 1000, PRICES) is None  # no invented price
    assert estimate_cost("m", None, None, PRICES) is None
    assert estimate_cost("m", 10, 10, {}) is None


class MemorySink:
    def __init__(self, fail=False):
        self.records, self.fail = [], fail

    async def write(self, record):
        if self.fail:
            raise RuntimeError("database down")
        self.records.append(record)


async def test_usage_record_is_logged_and_persisted(capsys):
    sink = MemorySink()
    recorder = UsageRecorder(PRICES, sink)
    capsys.readouterr()
    from app.obs.logging import configure_logging
    configure_logging("INFO")
    record = await recorder.record(operation="generate", model="m", tokens_in=1200, tokens_out=300, latency_ms=850,
                                   tier="reasoning", intent="product_search", customer_hash="abc")
    assert sink.records == [record]
    assert record.cost_estimate == pytest.approx(0.00111)
    assert record.cost_is_estimate is True and record.cost_currency == "USD"
    line = next(l for l in log_lines(capsys) if l["message"] == "model usage")
    assert (line["model"], line["tokens_in"], line["tokens_out"], line["latency_ms"]) == ("m", 1200, 300, 850)
    assert (line["tier"], line["intent"], line["cost_is_estimate"]) == ("reasoning", "product_search", True)


async def test_usage_record_carries_correlation_id():
    from app.obs.context import correlation_id_var
    token = correlation_id_var.set("trace-usage")
    try:
        record = await UsageRecorder({}, None).record(operation="embed", model="e", tokens_in=None, tokens_out=None,
                                                      latency_ms=5)
    finally:
        correlation_id_var.reset(token)
    assert record.correlation_id == "trace-usage"
    assert record.cost_estimate is None
    assert record.ts.tzinfo is not None


async def test_usage_persistence_failure_never_breaks_the_request():
    record = await UsageRecorder(PRICES, MemorySink(fail=True)).record(
        operation="generate", model="m", tokens_in=1, tokens_out=1, latency_ms=1)
    assert record.model == "m"


def test_dsports_calls_forward_the_customers_token_and_correlation_id():
    from app.obs.context import correlation_id_var
    from app.tools.dsports_http import forward_headers
    token = correlation_id_var.set("trace-fwd")
    try:
        assert forward_headers("customer.jwt.value") == {"X-Correlation-Id": "trace-fwd",
                                                         "Authorization": "Bearer customer.jwt.value"}
        assert "Authorization" not in forward_headers()  # unauthenticated probes carry no credential
    finally:
        correlation_id_var.reset(token)
