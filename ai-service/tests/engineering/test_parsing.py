from __future__ import annotations

import json

import pytest

from app.engineering.parsers import parse
from app.engineering.redact import REDACTED, redact_log_text
from app.engineering.signature import normalise, signature_of

CORR = "5b78d357-6fac-4812-9cbf-8fa57e30a3a2"
JAVA = [
    f"2026-10-13T16:15:06.020Z  WARN [{CORR}] 21272 --- [dsports-ai] [ctor-http-nio-9] c.d.exception.GlobalExceptionHandler     : Order ORD-20261007-000175 failed",
    f"2026-10-13T16:15:07.000Z ERROR [{CORR}] 21272 --- [dsports-ai] [ctor-http-nio-9] c.d.payment.PaymentService               : Capture failed status=503",
    "java.util.concurrent.TimeoutException: Timeout on blocking read for 30000 ms",
    "\tat com.dsports.payment.Gateway.capture(Gateway.java:88)",
    "\tat com.dsports.payment.PaymentService.pay(PaymentService.java:41)",
    "Caused by: io.r2dbc.postgresql.PostgresConnectionException: connection refused to 10.0.0.5:5432",
    "2026-10-13T16:15:08.000Z DEBUG [] 21272 --- [dsports-ai] [main] o.s.r2dbc.core.DefaultDatabaseClient     : Executing SQL statement [SELECT 1",
    "   FROM x]",
]


def test_java_lines_become_records_with_stack_traces_attached_and_debug_filtered():
    records, stats = parse("java", JAVA, "backend.log", "backend", "INFO")
    assert [r.level for r in records] == ["WARN", "ERROR"] and stats.skipped_below_level == 1
    err = records[1]
    assert err.correlation_id == CORR and err.http_status == 503 and "TimeoutException" in err.exception and "Gateway.capture" in err.exception
    assert err.signature and records[0].signature != err.signature


def test_the_same_failure_gets_the_same_signature_whatever_ids_numbers_or_lines_differ():
    a = signature_of("Could not expire unpaid order ORD-20261007-000175: payment is being captured after 30 s")
    b = signature_of("Could not expire unpaid order ORD-20261008-000999: payment is being captured after 45 s")
    c = signature_of("Could not expire unpaid order ORD-1: a different reason")
    assert a[0] == b[0] != c[0]
    e1 = "java.lang.IllegalStateException: pool 7 exhausted\n\tat com.dsports.db.Pool.acquire(Pool.java:10)"
    e2 = "java.lang.IllegalStateException: pool 9 exhausted\n\tat com.dsports.db.Pool.acquire(Pool.java:99)"
    assert signature_of("x", e1)[0] == signature_of("y", e2)[0]
    assert signature_of("x", e1)[1].startswith("IllegalStateException")


def test_the_root_cause_exception_defines_the_signature():
    exc = "org.springframework.X: wrapper\nCaused by: java.net.ConnectException: Connection refused"
    assert signature_of("m", exc)[1].startswith("ConnectException")


@pytest.mark.parametrize("raw,leaked", [
    ("password=hunter2 user ok", "hunter2"),
    ("Authorization: Bearer abcdefghijklmnop12345", "abcdefghijklmnop12345"),
    ('{"token": "abc.def.ghi-secret"}', "abc.def.ghi-secret"),
    ("contact asha.rao@example.com now", "asha.rao@example.com"),
    ("call 9876543210 please", "9876543210"),
    ("card 4111 1111 1111 1111 declined", "4111 1111 1111 1111"),
    ("r2dbc:postgresql://dsports:dsports123@localhost:5432/dsports failed", "dsports123"),
    ("customerId=aaaaaaaa-0000-0000-0000-000000000001 not found", "aaaaaaaa-0000-0000-0000-000000000001"),
    ("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig12345 seen", "eyJhbGciOiJIUzI1NiJ9"),
    ("api_key: sk-live-123456789", "sk-live-123456789"),
])
def test_secrets_and_personal_data_are_redacted_before_storage(raw, leaked):
    out = redact_log_text(raw)
    assert leaked not in out and (REDACTED in out or "[" in out)


def test_correlation_ids_survive_redaction_because_they_are_needed_for_correlation():
    assert CORR in redact_log_text(f"request {CORR} failed")


def test_parsed_records_are_redacted_and_deduplicated_by_position():
    line = f"2026-10-13T16:00:00.000Z ERROR [{CORR}] 1 --- [a] [t] c.d.X : login failed password=hunter2 for a@b.com"
    records, _ = parse("java", [line, line], "f.log", "backend")
    assert len(records) == 2 and records[0].raw_hash != records[1].raw_hash        # identical lines at different positions are both kept
    again, _ = parse("java", [line, line], "f.log", "backend")
    assert [r.raw_hash for r in again] == [r.raw_hash for r in records]            # ...and re-ingesting yields the same keys (idempotent)
    assert "hunter2" not in records[0].message and "a@b.com" not in records[0].message


def test_ai_service_json_lines_are_parsed_and_foreign_lines_counted_not_guessed():
    lines = [json.dumps({"ts": "2026-10-13T16:48:24.955+00:00", "level": "ERROR", "logger": "ai.request", "message": "request completed", "correlationId": CORR,
                         "method": "POST", "path": "/v1/chat", "status": 500, "duration_ms": 12}), "INFO:     Application startup complete.", ""]
    records, stats = parse("json", lines, "ai.log", "ai-service")
    assert len(records) == 1 and records[0].http_status == 500 and records[0].correlation_id == CORR and stats.unparsed == 1


def test_docker_infrastructure_lines_map_postgres_levels():
    lines = ["2026-10-13T10:00:00.000000000Z 2026-10-13 10:00:00.000 UTC [60] FATAL:  terminating connection due to administrator command",
             "2026-10-13T10:00:01.000000000Z 2026-10-13 10:00:01.000 UTC [61] LOG:  checkpoint complete"]
    records, _ = parse("docker", lines, "docker:dsports-postgres", "postgres", "INFO")
    assert [r.level for r in records] == ["ERROR", "INFO"]


def test_normalise_keeps_the_words_that_identify_the_failure():
    assert normalise("Timeout after 30000 ms calling 10.0.0.5:5432 for ORD-20261007-000175") == "Timeout after <n> ms calling <ip> for <ref>"
