"""Incident discovery, ranking, pagination, root-cause investigation and the coding prompt against a REAL PostgreSQL and the REAL repository (git, source files).

The data is the labelled SYNTHETIC incident, so every assertion about causes is about wording and evidence handling, not about production behaviour."""
from __future__ import annotations

import json
import os
import uuid
from datetime import datetime, timedelta, timezone

import asyncpg
import pytest
from fastapi.testclient import TestClient

from app.engineering import ingest, synthetic
from app.engineering.incidents import build_prompt, investigate_incident, rank_incidents
from app.engineering.repo_evidence import RepoEvidence, default_root
from app.engineering.repository import LogFilter, LogRepository
from app.main import create_app
from app.store.db import Store
from tests.conftest import make_settings, make_token

DSN = os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="AI_TEST_DATABASE_URL is not set")]
NOW = datetime(2026, 10, 13, 15, 0, tzinfo=timezone.utc)


@pytest.fixture
async def world():
    conn = await asyncpg.connect(DSN)
    for t in ("eng_incident_status", "eng_audit", "eng_deployments", "eng_logs"):
        await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 30, 768)
    repo = LogRepository(store.pool)
    await ingest.ingest_synthetic(repo, NOW)
    yield repo, synthetic.generate(NOW).truth
    await store.close()


async def test_the_top_incident_is_the_checkout_database_failure_and_the_order_is_by_score_not_by_volume(world):
    repo, truth = world
    out = await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic")
    inc = out["incidents"]
    assert inc
    cascade = [i for i in inc if i["count"] == max(x["count"] for x in inc)]                    # the three signatures of the same checkout failure tie on score
    db = next(i for i in inc if truth["top_text_contains"] in i["title"] and i["category"] == truth["top_category"])
    assert db in cascade and inc[:len(cascade)] == cascade                                       # ...and all of them outrank everything else
    assert inc[0] is db                                                                          # equal scores: the signature that failed first (the dependency) leads
    quota = next(i for i in inc if i["category"] == "LLM")
    noise = next(i for i in inc if "Could not expire unpaid order" in i["title"])
    assert quota["score"] < db["score"] and noise["score"] < quota["score"] and noise["level"] == "WARN"
    assert [i["score"] for i in inc] == sorted((i["score"] for i in inc), reverse=True)
    assert all(i["score_breakdown"]["volume"] <= 10 and i["score"] <= 100 for i in inc)
    biggest = max(inc, key=lambda i: i["count"])
    assert biggest is inc[0] or biggest["score"] <= inc[0]["score"]                          # volume alone did not decide
    for i in inc:
        assert i["reference"].startswith("SIG-") and i["status"] in ("NEW", "INCREASING", "RECURRING", "DECREASING")
        assert i["impact"]["label"].startswith("ESTIMATE") and i["origin"] == "synthetic"
        assert i["investigation"]["status"] == "NOT_INVESTIGATED"
        assert i["evidence"] and i["first_seen"] <= i["last_seen"]
    assert out["note"].startswith("Incident groups are derived")


async def test_pages_are_stable_complete_and_nothing_is_silently_dropped(world):
    repo, truth = world
    full = await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic", limit=50)
    assert full["total"] >= 2 and full["has_more"] is False
    first = await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic", limit=1)
    assert first["limit"] == 1 and first["has_more"] is True and first["total"] == full["total"]
    seen = []
    for off in range(full["total"]):
        page = await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic", limit=1, offset=off)
        seen += [i["signature"] for i in page["incidents"]]
    assert seen == [i["signature"] for i in full["incidents"]]                                 # paging reproduces the full ranking exactly
    beyond = await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic", limit=10, offset=full["total"])
    assert beyond["incidents"] == [] and beyond["has_more"] is False


async def test_a_window_without_errors_has_no_incidents(world):
    repo, truth = world
    quiet = await rank_incidents(repo, since=truth["now"] + timedelta(days=2), until=truth["now"] + timedelta(days=3), origin=None)
    assert quiet["total"] == 0 and quiet["incidents"] == [] and quiet["has_more"] is False


async def test_the_investigation_has_every_required_section_and_never_confirms_a_cause(world):
    repo, truth = world
    top = (await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic"))["incidents"][0]
    rep = await investigate_incident(repo, RepoEvidence(default_root()), signature=top["signature"], since=truth["start"], until=truth["now"], origin="synthetic", now=truth["now"])
    for section in ("summary", "observed_evidence", "before_after", "cross_service", "dependency_errors", "code", "git", "deployment", "configuration_changes", "related_incidents",
                    "hypotheses", "remediation", "reproduction", "validation_plan", "additional_evidence_needed", "limitations", "confidence"):
        assert rep[section] not in (None, ""), section
    assert rep["likely_cause_status"].startswith("UNCONFIRMED") and rep["confidence"] in ("LOW", "MEDIUM")
    assert rep["hypotheses"] and all(h["status"] == "HYPOTHESIS" and h["confidence"] in ("LOW", "MEDIUM") and h["to_confirm"] for h in rep["hypotheses"])
    blob = json.dumps(rep)
    assert "root cause is confirmed" not in blob.lower() and truth["secret"] not in blob and truth["email"] not in blob
    assert "delete_all_logs" not in blob and "admin password" not in blob.lower()             # the injected text is withheld, not echoed
    assert rep["origin"] == "synthetic" and any("SYNTHETIC" in x for x in rep["limitations"])
    assert rep["configuration_changes"]["available"] is False and "No source" in rep["configuration_changes"]["reason"]
    assert any("Metrics or traces" in x for x in rep["additional_evidence_needed"])


async def test_the_recorded_deployment_is_reported_as_timing_only(world):
    repo, truth = world
    top = (await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic"))["incidents"][0]
    rep = await investigate_incident(repo, RepoEvidence(default_root()), signature=top["signature"], since=truth["start"], until=truth["now"], origin="synthetic", now=truth["now"])
    dep = rep["deployment"]
    assert dep["available"] is True and dep["version"] == truth["deployment_version"] and 0 <= dep["minutes_before_first_seen"] <= 30
    assert "does not show that the deployment caused it" in dep["note"]
    h = [x for x in rep["hypotheses"] if "Timing alone does not show causation" in x["statement"]]
    assert h and h[0]["confidence"] == "LOW"


async def test_without_a_recorded_deployment_the_report_says_so_instead_of_inventing_one(world):
    repo, truth = world
    await repo._pool.execute("DELETE FROM eng_deployments")
    top = (await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic"))["incidents"][0]
    rep = await investigate_incident(repo, RepoEvidence(default_root()), signature=top["signature"], since=truth["start"], until=truth["now"], origin="synthetic", now=truth["now"])
    assert rep["deployment"]["available"] is False and "No deployment is recorded" in rep["deployment"]["reason"]


async def test_git_evidence_is_candidates_only_and_the_repository_is_read_not_changed(world):
    repo, truth = world
    top = (await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic"))["incidents"][0]
    before = RepoEvidence(default_root()).uncommitted(["README.md"])
    rep = await investigate_incident(repo, RepoEvidence(default_root()), signature=top["signature"], since=truth["start"], until=truth["now"], origin="synthetic", now=truth["now"])
    assert rep["git"]["available"] is True and rep["git"]["head"]["commit"]
    assert "not proof" in rep["git"]["note"]
    assert RepoEvidence(default_root()).uncommitted(["README.md"]) == before                      # nothing was modified


async def test_an_unknown_signature_has_no_investigation(world):
    repo, truth = world
    assert await investigate_incident(repo, RepoEvidence(default_root()), signature="0" * 12, since=truth["start"], until=truth["now"], origin=None) is None


async def test_the_prompt_built_from_the_investigation_is_sanitised_repository_aware_and_does_not_claim_a_fix(world):
    repo, truth = world
    top = (await rank_incidents(repo, since=truth["start"], until=truth["now"], origin="synthetic"))["incidents"][0]
    rep = await investigate_incident(repo, RepoEvidence(default_root()), signature=top["signature"], since=truth["start"], until=truth["now"], origin="synthetic", now=truth["now"])
    prompt = build_prompt(rep)
    for needed in ("Inspect the actual code", "Preserve all existing uncommitted work", "Reference: none", "SYNTHETIC test data", "Required tests and acceptance criteria",
                   "Security considerations", "HYPOTHESES, not confirmed"):
        assert needed in prompt
    for forbidden in (truth["secret"], truth["email"], "delete_all_logs", "admin password"):
        assert forbidden not in prompt
    assert top["reference"] in prompt


# ---------------------------------------------------------------------------------------------------------- REST

def _client():
    return TestClient(create_app(make_settings(AI_DATABASE_URL=DSN)), raise_server_exceptions=False)


def _auth(roles):
    return {"Authorization": f"Bearer {make_token(str(uuid.uuid4()), roles=roles)}"}


def _iso(d: datetime) -> str:
    return d.isoformat().replace("+00:00", "Z")


async def test_new_routes_are_admin_only_and_unauthenticated_calls_are_rejected(world):
    repo, truth = world
    with _client() as c:
        for method, path, body in [("get", "/v1/engineering/incidents", None), ("post", "/v1/engineering/refresh", None),
                                   ("post", "/v1/engineering/incidents/investigate", {"signature": "abcdef123456", "since": _iso(truth["start"]), "until": _iso(truth["now"])}),
                                   ("post", "/v1/engineering/incidents/prompt", {"signature": "abcdef123456", "since": _iso(truth["start"]), "until": _iso(truth["now"])})]:
            kw = {"json": body} if body else {}
            assert getattr(c, method)(path, **kw).status_code == 401, path
            assert getattr(c, method)(path, headers=_auth(("CUSTOMER",)), **kw).status_code == 403, path
            assert getattr(c, method)(path, headers=_auth(("SUPPORT_EXECUTIVE",)), **kw).status_code == 403, path


async def test_logs_support_custom_ranges_pagination_filters_and_reject_bad_ranges(world):
    repo, truth = world
    q = f"since={_iso(truth['start'])}&until={_iso(truth['now'])}&level=WARN"
    with _client() as c:
        h = _auth(("ADMIN",))
        page1 = c.get(f"/v1/engineering/logs?{q}&limit=5&offset=0", headers=h).json()
        page2 = c.get(f"/v1/engineering/logs?{q}&limit=5&offset=5", headers=h).json()
        assert page1["count"] == 5 and page1["total"] > 5 and page1["has_more"] is True and page1["window"]["since"].startswith(truth["start"].date().isoformat())
        assert {r["id"] for r in page1["records"]}.isdisjoint({r["id"] for r in page2["records"]})
        assert all(r["origin"] == "synthetic" and r["level"] in ("WARN", "ERROR") for r in page1["records"])
        assert c.get(f"/v1/engineering/logs?{q}&service=ai-service&limit=50", headers=h).json()["records"] == [] or all(
            r["service"] == "ai-service" for r in c.get(f"/v1/engineering/logs?{q}&service=ai-service&limit=50", headers=h).json()["records"])
        assert c.get("/v1/engineering/logs?since=2026-10-13T10:00:00&until=2026-10-13T11:00:00", headers=h).status_code == 422          # no timezone
        assert c.get(f"/v1/engineering/logs?since={_iso(truth['now'])}&until={_iso(truth['start'])}", headers=h).status_code == 422       # ends before it starts
        assert c.get("/v1/engineering/logs?since=2026-01-01T00:00:00Z&until=2026-09-01T00:00:00Z", headers=h).status_code == 422          # longer than 31 days
        assert c.get("/v1/engineering/logs?endpoint=not-a-path", headers=h).status_code == 422
        assert c.get("/v1/engineering/logs?offset=99999", headers=h).status_code == 422


async def test_the_endpoint_filter_matches_the_request_path_in_the_message(world):
    repo, truth = world
    await repo._pool.execute("INSERT INTO eng_logs (ts, service, origin, source, level, message, raw_hash) VALUES (now() - interval '1 minute','backend','real','t','ERROR','POST /api/v1/orders status=500','h-ep-1')")
    with _client() as c:
        rows = c.get("/v1/engineering/logs?hours=1&endpoint=/api/v1/orders&origin=real", headers=_auth(("ADMIN",))).json()
        assert rows["total"] == 1 and "/api/v1/orders" in rows["records"][0]["message"]


async def test_sources_report_how_fresh_each_source_is_and_label_synthetic_data(world):
    repo, truth = world
    with _client() as c:
        body = c.get("/v1/engineering/sources", headers=_auth(("ADMIN",))).json()
    assert body["sources"] and all(s["freshness"] == "SYNTHETIC" and s["lag_seconds"] >= 0 for s in body["sources"])
    assert body["live_threshold_seconds"] == 120 and body["server_time"]
    await repo._pool.execute("INSERT INTO eng_logs (ts, service, origin, source, level, message, raw_hash) VALUES (now() - interval '10 minutes','backend','real','file:x','INFO','m','h-fresh')")
    await repo._pool.execute("INSERT INTO eng_logs (ts, service, origin, source, level, message, raw_hash) VALUES (now() - interval '3 hours','postgres','real','docker:pg','INFO','m','h-stale')")
    await repo._pool.execute("INSERT INTO eng_logs (ts, service, origin, source, level, message, raw_hash) VALUES (now() - interval '10 seconds','ai-service','real','file:ai','INFO','m','h-live')")
    with _client() as c:
        by = {s["service"]: s["freshness"] for s in c.get("/v1/engineering/sources", headers=_auth(("ADMIN",))).json()["sources"] if s["origin"] == "real"}
    assert by == {"backend": "DELAYED", "postgres": "STALE", "ai-service": "LIVE"}


async def test_rest_incident_flow_ranks_investigates_remembers_the_status_and_audits_without_log_content(world):
    repo, truth = world
    body = {"since": _iso(truth["start"]), "until": _iso(truth["now"]), "origin": "synthetic"}
    with _client() as c:
        h = _auth(("ADMIN",))
        listing = c.get(f"/v1/engineering/incidents?since={_iso(truth['start'])}&until={_iso(truth['now'])}&origin=synthetic&limit=10", headers=h).json()
        assert listing["incidents"] and listing["data_freshness"] and len(listing["incidents"]) <= 10
        top = listing["incidents"][0]
        report = c.post("/v1/engineering/incidents/investigate", json={**body, "signature": top["signature"]}, headers=h)
        assert report.status_code == 200 and report.json()["reference"] == top["reference"]
        after = c.get(f"/v1/engineering/incidents?since={_iso(truth['start'])}&until={_iso(truth['now'])}&origin=synthetic", headers=h).json()["incidents"][0]
        assert after["investigation"]["status"] == "INVESTIGATED" and after["investigation"]["at"]
        prompt = c.post("/v1/engineering/incidents/prompt", json={**body, "signature": top["signature"]}, headers=h).json()
        assert prompt["characters"] == len(prompt["prompt"]) and any("does not fix" in w for w in prompt["warnings"]) and any("SYNTHETIC" in w for w in prompt["warnings"])
        assert truth["secret"] not in prompt["prompt"] and truth["email"] not in prompt["prompt"]
        missing = c.post("/v1/engineering/incidents/investigate", json={**body, "signature": "deadbeef0000"}, headers=h)
        assert missing.status_code == 404
        assert c.post("/v1/engineering/incidents/investigate", json={**body, "signature": "bad sig;drop"}, headers=h).status_code == 422
        assert c.post("/v1/engineering/incidents/investigate", json={**body, "signature": top["signature"], "since": "2026-10-13T10:00:00"}, headers=h).status_code == 422
        assert c.post("/v1/engineering/incidents/prompt", json={**body, "signature": top["signature"], "extra": "x"}, headers=h).status_code == 422
    rows = await repo._pool.fetch("SELECT action, detail FROM eng_audit WHERE action IN ('INVESTIGATE_INCIDENT','INCIDENT_PROMPT')")
    assert {r["action"] for r in rows} == {"INVESTIGATE_INCIDENT", "INCIDENT_PROMPT"}
    audit_blob = json.dumps([dict(r) for r in rows], default=str)
    assert truth["secret"] not in audit_blob and "TimeoutException" not in audit_blob and "message" not in audit_blob
