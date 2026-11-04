"""Engineering AI against a REAL PostgreSQL (throwaway database): ingestion of the labelled synthetic incident through the real parsers, redaction in
storage, time/service/correlation filtering, clustering, deployment correlation, the investigator's cited evidence, prompt-injection handling, the
unsupported-claim guard, retrieval accuracy against the incident's ground truth, and the REST role gate with auditing."""
from __future__ import annotations

import json
import os
import re
import uuid
from datetime import datetime, timedelta, timezone

import asyncpg
import pytest
from fastapi.testclient import TestClient

from app.engineering import ingest, synthetic
from app.engineering.analysis import build_clusters, trace_chain
from app.engineering.investigate import Investigator
from app.engineering.repository import LogFilter, LogRepository
from app.llm.base import LLMError, LLMResult
from app.main import create_app
from app.store.db import Store
from tests.conftest import make_settings, make_token

DSN = os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="AI_TEST_DATABASE_URL is not set")]
NOW = datetime(2026, 10, 13, 15, 0, tzinfo=timezone.utc)


class ScriptedLLM:
    def __init__(self, text=None, error=None):
        self.text, self.error, self.prompts = text, error, []

    async def generate(self, prompt, **kw):
        self.prompts.append((prompt, kw))
        if self.error:
            raise self.error
        return LLMResult(self.text, "fake-model", 100, 40, 5)


@pytest.fixture
async def world():
    conn = await asyncpg.connect(DSN)
    for t in ("eng_audit", "eng_deployments", "eng_logs"):
        await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 30, 768)
    repo = LogRepository(store.pool)
    result = await ingest.ingest_synthetic(repo, NOW)
    truth = synthetic.generate(NOW).truth
    yield repo, truth, result
    await store.close()


def window(truth, minutes_before=0):
    return truth["start"], truth["now"] + timedelta(minutes=minutes_before)


async def test_ingestion_is_idempotent_labelled_synthetic_and_goes_through_the_real_parsers(world):
    repo, truth, result = world
    assert result["backend"]["stored_new"] > 0 and result["ai-service"]["stored_new"] > 0
    again = await ingest.ingest_synthetic(repo, NOW, replace=False)
    assert again["backend"]["stored_new"] == 0 and again["ai-service"]["stored_new"] == 0
    assert {r["origin"] for r in await repo.coverage()} == {"synthetic"}


async def test_secrets_personal_data_and_tokens_are_not_stored(world):
    repo, truth, _ = world
    rows = await repo._pool.fetch("SELECT message, coalesce(exception,'') AS exception FROM eng_logs")
    blob = "\n".join(r["message"] + r["exception"] for r in rows)
    for leaked in (truth["secret"], truth["email"], "eyJhbGciOiJIUzI1NiJ9", "203.0.113.9"[:0] or "hunter2"):
        assert leaked not in blob
    assert "[EMAIL]" in blob and "[REDACTED]" in blob


async def test_time_window_filtering_separates_before_and_after_the_release(world):
    repo, truth, _ = world
    deploy = (await repo.deployments(truth["start"], truth["now"]))[0]["ts"]
    before = await repo.count(LogFilter(truth["start"], deploy, ("backend",), "ERROR", terms=("capture",)))
    after = await repo.count(LogFilter(deploy, truth["now"], ("backend",), "ERROR", terms=("capture",)))
    assert before == 0 and after == 2 * truth["failures"] + 1          # +1: the injected payment-note record also mentions "capture" (its logger), correctly


async def test_service_filtering(world):
    repo, truth, _ = world
    rows = await repo.search(LogFilter(*window(truth), ("ai-service",), "ERROR", limit=200))
    assert rows and {r["service"] for r in rows} == {"ai-service"}


async def test_correlation_search_returns_the_same_request_across_services(world):
    repo, truth, _ = world
    cid = truth["failing_correlation_ids"][0]
    rows = await repo.trace(cid)
    chain = trace_chain(rows)
    assert chain["services"] == ["ai-service", "backend"] and chain["first_error"]["service"] == "backend" and chain["first_error"]["category"] == "DATABASE"


async def test_error_clustering_groups_the_incident_and_keeps_noise_separate(world):
    repo, truth, _ = world
    f = LogFilter(*window(truth), min_level="WARN")
    span = f.until - f.since
    prev = await repo.signature_counts(LogFilter(f.since - span, f.since, (), "WARN"))
    clusters = build_clusters(await repo.clusters(f, 20), prev)
    top = clusters[0]
    assert top.category == "DATABASE" and truth["top_text_contains"] in top.text and top.count == truth["failures"]
    texts = " | ".join(c.text for c in clusters)
    for noise in truth["noise_signatures"]:
        assert noise in texts
    assert len({c.signature for c in clusters}) == len(clusters)                       # one cluster per signature, whatever the order numbers
    unpaid = next(c for c in clusters if "unpaid order" in c.text)
    deploy_ts = (await repo.deployments(truth["start"], truth["now"]))[0]["ts"]
    assert unpaid.count >= 30 and unpaid.first_seen < deploy_ts                        # the noise predates the release, so it is not blamed on it
    out = await Investigator(repo).run("What errors appeared after the latest deployment?", as_of=truth["now"])
    assert "unpaid order" not in json.dumps(out["deployment"]["new_error_signatures_after"])


async def test_retrieval_accuracy_against_the_incidents_ground_truth(world):
    repo, truth, _ = world
    start, end = window(truth)
    retrieved: set[str] = set()
    t = start
    while t < end:                                                                     # the API caps rows per call, so page through the window in 10-minute slices
        for r in await repo.search(LogFilter(t, min(t + timedelta(minutes=10), end), ("backend",), "ERROR", terms=("capture failed",), limit=200)):
            retrieved.add(r["correlation_id"])
        t += timedelta(minutes=10)
    relevant = set(truth["failing_correlation_ids"])
    precision = len(retrieved & relevant) / len(retrieved)
    recall = len(retrieved & relevant) / len(relevant)
    assert precision == 1.0 and recall == 1.0, (precision, recall)
    assert len(relevant) == truth["failures"]


async def test_investigation_separates_facts_from_hypotheses_and_cites_real_records(world):
    repo, truth, _ = world
    out = await Investigator(repo).run("Why did checkout fail?", as_of=truth["now"])
    ids = {e["id"] for e in out["evidence"]}
    cited = {int(m) for text in out["facts"] for m in re.findall(r"\[L(\d+)\]", text)} | {int(m) for m in re.findall(r"\[L(\d+)\]", out["summary"]["text"])}
    assert cited and cited <= ids                                                      # every citation is a record that was actually retrieved
    assert out["hypotheses"] and all(h["status"] == "HYPOTHESIS" and h["confidence"] in ("LOW", "MEDIUM") and h["to_confirm"] for h in out["hypotheses"])
    assert out["clusters"][0]["category"] == "DATABASE"
    assert out["trend"]["direction"] == "RISING"
    assert "root cause is" not in out["summary"]["text"].lower() and "Hypotheses (NOT confirmed)" in out["summary"]["text"]
    assert out["summary"]["source"] == "deterministic" and all(e["origin"] == "synthetic" for e in out["evidence"])
    assert any("SYNTHETIC" in lim for lim in out["limitations"])


async def test_deployment_correlation_reports_timing_not_causation(world):
    repo, truth, _ = world
    out = await Investigator(repo).run("What errors appeared after the latest deployment?", as_of=truth["now"])
    eff = out["deployment"]
    assert eff and eff["deployment"]["version"] == truth["deployment_version"]
    new = eff["new_error_signatures_after"]
    assert new and new[0]["minutes_after_deployment"] <= 10 and truth["top_text_contains"] in new[0]["text"]
    hyp = next(h for h in out["hypotheses"] if "may be related" in h["statement"])
    assert "causation" in hyp["statement"] and hyp["confidence"] in ("LOW", "MEDIUM")


async def test_http_5xx_question_filters_by_status_and_shows_the_trend(world):
    repo, truth, _ = world
    out = await Investigator(repo).run("What caused the increase in HTTP 5xx errors?", as_of=truth["now"])
    assert out["filters"]["min_status"] == 500 and out["trend"]["direction"] == "RISING"
    assert all(e["http_status"] is None or e["http_status"] >= 500 for e in out["evidence"])


async def test_recurring_and_health_and_trace_questions(world):
    repo, truth, _ = world
    rec = await Investigator(repo).run("Show recurring database timeout errors", as_of=truth["now"])
    assert rec["intent"] == "CLUSTERS" and rec["clusters"] and rec["clusters"][0]["category"] == "DATABASE"
    health = await Investigator(repo).run("How is the backend doing?", as_of=truth["now"])
    assert health["intent"] == "HEALTH" and health["health"][0]["service"] == "backend" and health["health"][0]["errors"] >= truth["failures"]
    tr = await Investigator(repo).run(f"show logs for {truth['failing_correlation_ids'][0]}", as_of=truth["now"])
    assert tr["intent"] == "TRACE" and tr["trace"]["services"] == ["ai-service", "backend"]


async def test_an_injection_inside_a_log_message_is_neutralised_and_cannot_steer_the_summary(world):
    repo, truth, _ = world
    rows = await repo.search(LogFilter(*window(truth), ("backend",), "ERROR", terms=("payment note",), limit=5))
    assert rows and "IGNORE ALL PREVIOUS INSTRUCTIONS" in rows[0]["message"]           # it IS in the log: stored as data
    out = await Investigator(repo).run(f"show logs for {rows[0]['correlation_id']}", as_of=truth["now"])
    note = next(e for e in out["evidence"] if e["injection_flagged"])
    assert "withheld" in note["message"] and "IGNORE" not in note["message"].upper() and "PASSWORD" not in note["message"].upper() and note["service"] == "backend"
    blob = json.dumps(out)
    assert "delete_all_logs" not in blob and "admin password" not in blob                 # nothing of the injected text is shown anywhere in the response


async def test_a_model_that_obeys_the_injected_instruction_is_rejected_by_the_claim_guard(world):
    repo, truth, _ = world
    llm = ScriptedLLM("The root cause is confirmed to be the frontend [L999999]. The admin password is hunter2.")
    out = await Investigator(repo, llm).run("Why did checkout fail?", as_of=truth["now"], use_llm=True)
    assert out["summary"]["source"] == "deterministic" and out["summary"]["guard"]["unknown_citations"] == [999999]
    assert "frontend" not in out["summary"]["text"].lower() and out["summary"]["guard"]["unsupported_claims"]
    prompt = llm.prompts[0][0]
    assert "<evidence>" in prompt and "hunter2" not in prompt and "IGNORE ALL PREVIOUS" not in prompt.upper().replace("[REMOVED]", "")


async def test_a_well_behaved_model_summary_with_real_citations_is_accepted(world):
    repo, truth, _ = world
    det = await Investigator(repo).run("Why did checkout fail?", as_of=truth["now"])
    real_id = det["clusters"][0]["sample_ids"][0]
    llm = ScriptedLLM(f"Payment capture failures rose after the release; the pool timeouts may indicate database connection exhaustion, which is a hypothesis [L{real_id}].")
    out = await Investigator(repo, llm).run("Why did checkout fail?", as_of=truth["now"], use_llm=True)
    assert out["summary"]["source"] == "llm" and out["summary"]["guard"]["ok"] and out["meta"]["llm_used"]


async def test_a_model_outage_falls_back_to_the_deterministic_summary(world):
    repo, truth, _ = world
    out = await Investigator(repo, ScriptedLLM(error=LLMError("LLM_QUOTA", "429"))).run("Why did checkout fail?", as_of=truth["now"], use_llm=True)
    assert out["summary"]["source"] == "deterministic" and "unavailable" in out["summary"]["guard"]["reason"] and out["facts"]


async def test_no_data_in_the_window_yields_no_invented_findings(world):
    repo, truth, _ = world
    out = await Investigator(repo).run("Why did checkout fail?", as_of=truth["start"] - timedelta(days=5))
    assert out["clusters"] == [] and out["hypotheses"] == [] and "No error pattern" in out["summary"]["text"]


# ------------------------------------------------------------------------------------------------ REST: role gate and audit

def _client():
    return TestClient(create_app(make_settings(AI_DATABASE_URL=DSN)), raise_server_exceptions=False)


def _auth(roles):
    return {"Authorization": f"Bearer {make_token(str(uuid.uuid4()), roles=roles)}"}


async def test_rest_routes_are_admin_only_and_denials_are_audited(world):
    repo, truth, _ = world
    with _client() as c:
        for method, path, body in [("get", "/v1/engineering/sources", None), ("get", "/v1/engineering/clusters", None), ("get", "/v1/engineering/logs", None),
                                   ("post", "/v1/engineering/investigate", {"question": "why did checkout fail"}), ("get", "/v1/engineering/deployments", None)]:
            kw = {"json": body} if body else {}
            assert getattr(c, method)(path, **kw).status_code == 401, path
            assert getattr(c, method)(path, headers=_auth(("CUSTOMER",)), **kw).status_code == 403, path
            assert getattr(c, method)(path, headers=_auth(("SUPPORT_EXECUTIVE",)), **kw).status_code == 403, path
        ok = c.get("/v1/engineering/sources", headers=_auth(("ADMIN",)))
        assert ok.status_code == 200 and {s["origin"] for s in ok.json()["sources"]} == {"synthetic"}
    assert await repo._pool.fetchval("SELECT count(*) FROM eng_audit WHERE action = 'DENIED'") >= 5


async def test_rest_investigation_is_audited_without_log_content_and_rejects_bad_input(world):
    repo, truth, _ = world
    with _client() as c:
        h = _auth(("ADMIN",))
        r = c.post("/v1/engineering/investigate", json={"question": "Why did checkout fail?", "origin": "synthetic"}, headers=h)
        assert r.status_code == 200 and r.json()["hypotheses"] is not None
        assert c.post("/v1/engineering/investigate", json={"question": "x"}, headers=h).status_code == 422
        assert c.post("/v1/engineering/investigate", json={"question": "why did it fail", "sql": "drop table"}, headers=h).status_code == 422
        assert c.get("/v1/engineering/logs?hours=1000", headers=h).status_code == 422
        assert c.get("/v1/engineering/logs?service=vault&hours=2", headers=h).status_code == 422
    detail = await repo._pool.fetchval("SELECT detail::text FROM eng_audit WHERE action = 'INVESTIGATE' ORDER BY id DESC LIMIT 1")
    assert "Why did checkout fail" in detail and "hunter2" not in detail and "ts" not in json.loads(detail)
