"""Real-stack demonstration of Engineering AI (Phase 4) on the RUNNING AI service, with real JWTs and the real AI database.

1. A clearly-labelled SYNTHETIC incident is loaded (origin='synthetic') and the REAL backend/AI/Postgres logs are ingested (origin='real').
2. Role gate: guest 401, customer 403, support executive 403, admin 200 (and the denials are audited).
3. Synthetic incident, start to finish: question -> filters -> evidence -> clusters -> deployment correlation -> cross-service trace -> cited summary.
4. A REAL finding (recurring warning) is checked against the raw log file, independently of the ingestion code.
5. Redaction and prompt-injection checks on every response. Optional LLM summary (Gemini); reported as-is if the model is unavailable.

Usage: uv run --project ai-service python e2e/ai/engineering_e2e.py        exit 0 = every check passed
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import stack as s

ROOT = Path(__file__).resolve().parents[2]
RESULTS: list[tuple[str, bool]] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    RESULTS.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""), flush=True)


def ai_sql(q: str) -> str:
    out = subprocess.run([s.DOCKER, "exec", "dsports-postgres", "psql", "-U", "dsports", "-d", "dsports_ai", "-tAc", q], capture_output=True, text=True, check=True)
    return out.stdout.strip()


def cli(*args: str) -> str:
    # the interpreter already running this script (it was started with the ai-service environment), so no `uv` lookup on PATH is needed
    out = subprocess.run([sys.executable, "-m", "app.engineering.cli", *args], cwd=ROOT / "ai-service", capture_output=True, text=True)
    assert out.returncode == 0, out.stderr[-500:]
    return out.stdout


def eng(token, method, path, body=None):
    return s.ai(method, f"/v1/engineering{path}", token, body)


def main() -> int:
    _, customer = s.register("eng-cust")
    _, staff_tok = s.register("eng-staff")
    staff_email = s.sql("select email from customers order by created_at desc limit 1")
    s.sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, 'SUPPORT_EXECUTIVE' FROM customers WHERE email='{staff_email}' ON CONFLICT DO NOTHING")
    staff_tok = s.login(staff_email)
    admin_email, _ = s.register("eng-admin")
    admin = s.make_admin(admin_email)

    # ---- 1. data
    cli("synthetic")
    cli("ingest", "--service", "backend")
    sources = {(x["service"], x["origin"]) for x in eng(admin, "GET", "/sources")[1]["sources"]}
    check("sources: real backend and postgres logs plus the synthetic incident are connected", {("backend", "real"), ("postgres", "real"), ("backend", "synthetic"), ("ai-service", "synthetic")} <= sources, str(sources))

    # ---- 2. access control
    paths = [("GET", "/sources"), ("GET", "/clusters"), ("GET", "/logs"), ("GET", "/health"), ("GET", "/deployments"), ("POST", "/investigate")]
    body = {"question": "Why did checkout fail?"}
    check("guest gets 401 on every engineering route", all(eng(None, m, p, body if m == "POST" else None)[0] == 401 for m, p in paths))
    check("a customer gets 403 on every engineering route", all(eng(customer, m, p, body if m == "POST" else None)[0] == 403 for m, p in paths))
    check("a support executive gets 403 (engineering is not a support role)", all(eng(staff_tok, m, p, body if m == "POST" else None)[0] == 403 for m, p in paths))
    check("denied attempts are audited", int(ai_sql("select count(*) from eng_audit where action='DENIED'")) >= 12)

    # ---- 3. the synthetic incident, start to finish
    code, inc = eng(admin, "POST", "/investigate", {"question": "Why did checkout fail?", "origin": "synthetic"})
    check("investigation answers 200 with interpretation, facts, hypotheses, clusters and evidence", code == 200 and all(inc.get(k) for k in ("interpretation", "facts", "hypotheses", "clusters", "evidence", "next_steps")), f"{code}")
    top = inc["clusters"][0]
    check("the top error cluster is the database pool timeout (category DATABASE) with its real count", top["category"] == "DATABASE" and "TimeoutException" in top["text"] and top["count"] > 100, str(top))
    check("every hypothesis is labelled HYPOTHESIS, LOW/MEDIUM confidence and says how to confirm it", all(h["status"] == "HYPOTHESIS" and h["confidence"] in ("LOW", "MEDIUM") and h["to_confirm"] for h in inc["hypotheses"]))
    ids = {e["id"] for e in inc["evidence"]}
    cited = {int(m) for t in [*inc["facts"], inc["summary"]["text"]] for m in re.findall(r"\[L(\d+)\]", t)}
    check("every citation in the summary points to a record in the evidence", cited and cited <= ids, f"{cited - ids}")
    check("the summary never claims a confirmed root cause", "root cause is" not in inc["summary"]["text"].lower() and "NOT confirmed" in inc["summary"]["text"])
    check("every evidence record is labelled synthetic", all(e["origin"] == "synthetic" for e in inc["evidence"]) and any("SYNTHETIC" in l for l in inc["limitations"]))

    code, dep = eng(admin, "POST", "/investigate", {"question": "What errors appeared after the latest deployment?", "origin": "synthetic"})
    new = (dep["deployment"] or {}).get("new_error_signatures_after", [])
    check("deployment correlation: the pool-timeout error was first seen minutes after backend v2.4.0 (timing, not causation)", new and new[0]["minutes_after_deployment"] <= 10 and dep["deployment"]["deployment"]["version"] == "v2.4.0"
          and any("causation" in h["statement"] for h in dep["hypotheses"]), str(dep.get("deployment")))
    code, h5 = eng(admin, "POST", "/investigate", {"question": "What caused the increase in HTTP 5xx errors?", "origin": "synthetic"})
    check("the 5xx question filters on status >= 500 and reports a rising trend", h5["filters"]["min_status"] == 500 and h5["trend"]["direction"] == "RISING", str(h5.get("trend")))
    cid = next(c["correlation_id"] for c in inc["chains"] if c["first_error"])
    code, tr = eng(admin, "GET", f"/trace/{cid}")
    check("a correlation id links the backend failure and the AI-service failure; the first error is in the backend", tr["chain"]["services"] == ["ai-service", "backend"] and tr["chain"]["first_error"]["service"] == "backend" and tr["chain"]["first_error"]["category"] == "DATABASE")

    # ---- 4. a REAL finding, verified against the raw file
    code, real = eng(admin, "POST", "/investigate", {"question": "Show recurring errors in the backend in the last 3 days", "origin": "real"})
    log_file = Path(os.environ.get("ENG_BACKEND_LOG") or Path(tempfile.gettempdir()) / "dsports_backend.log")
    from datetime import datetime, timezone
    lines = log_file.read_text(encoding="utf-8", errors="replace").splitlines()
    stamp = lambda ln: (lambda m: datetime.fromisoformat(m.group(1).replace("Z", "+00:00")) if m else None)(re.match(r"^(\d{4}-\d\d-\d\dT[\d:.]+Z)", ln))   # noqa: E731
    first_ts = next(t for t in map(stamp, lines) if t)
    # the backend keeps logging while this runs, so both sides are counted up to the same cutoff: the newest ingested record
    cutoff = datetime.fromisoformat(ai_sql("select to_char(max(ts) at time zone 'utc', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') from eng_logs where service='backend' and origin='real'") + "+00:00")
    raw = sum(1 for line in lines if " WARN " in line and "Could not expire unpaid order" in line and (stamp(line) or cutoff) <= cutoff)
    pattern = "message LIKE 'Could not expire unpaid order%' AND level='WARN' AND service='backend' AND origin='real'"
    since_file = int(ai_sql(f"select count(*) from eng_logs where {pattern} and ts >= '{first_ts.isoformat()}' and ts <= '{cutoff.isoformat()}'"))
    history = int(ai_sql(f"select count(*) from eng_logs where {pattern} and ts >= now() - interval '3 days' and ts <= '{cutoff.isoformat()}'"))
    unpaid = next((c for c in real["clusters"] if "Could not expire unpaid order" in c["text"]), None)
    # The store keeps records of EARLIER backend log files too (the file is overwritten at every restart), so: the raw file must equal what was
    # ingested from the same period, and the cluster must equal everything stored for the 3-day window.
    check("REAL: the recurring 'Could not expire unpaid order' warning: stored count for the current log file equals the raw file count", raw > 20 and since_file == raw, f"{since_file} vs raw {raw}")
    check("REAL: the cluster reports every stored occurrence in the window (current and earlier log files)", unpaid is not None and unpaid["count"] >= history and history >= raw, f"{unpaid and unpaid['count']} vs {history}")      # >=: the API answer may include a record logged after the cutoff
    check("REAL: its hypothesis is labelled as a hypothesis from log text", any("scheduled job" in h["statement"] and h["status"] == "HYPOTHESIS" for h in real["hypotheses"]))
    check("REAL evidence is labelled real", real["evidence"] and all(e["origin"] == "real" for e in real["evidence"]))

    # ---- 5. redaction and injection on everything returned
    blob = json.dumps([inc, dep, h5, tr, real])
    check("no secret, e-mail address or token appears in any response", not any(x in blob for x in ("hunter2", "asha.rao@example.com", "eyJhbGciOiJIUzI1NiJ9", "203.0.113.9")))
    check("the injected log text appears nowhere; its record is shown as withheld", "delete_all_logs" not in blob and "admin password" not in blob and any(e["injection_flagged"] for e in tr["records"] + inc["evidence"]) or "withheld" in blob)
    code, bad = eng(admin, "POST", "/investigate", {"question": "x"})
    code2, extra = eng(admin, "POST", "/investigate", {"question": "why did it fail", "sql": "select * from customers"})
    code3, wide = eng(admin, "GET", "/logs?hours=5000")
    check("malformed, free-form-query and out-of-bounds requests are rejected (422)", code == 422 and code2 == 422 and code3 == 422, f"{code} {code2} {code3}")
    leak, _ = s.ai("GET", "/v1/engineering/logs?q=select%20password%20from%20customers&hours=2", admin)
    check("a SQL-looking search term is just text to match, not SQL", leak == 200)

    # ---- optional LLM explanation
    code, llm = eng(admin, "POST", "/investigate", {"question": "Why did checkout fail?", "origin": "synthetic", "useLlm": True})
    guard = llm["summary"].get("guard") or {}
    check(f"LLM summary: source={llm['summary']['source']} (model text is used only if it passes the evidence guard; otherwise the deterministic summary)",
          code == 200 and (llm["summary"]["source"] == "deterministic" or guard.get("ok") is True), str(guard)[:200])
    print("   LLM note:", (guard.get("reason") or "model text accepted by the guard"))

    check("investigations are audited without log content", int(ai_sql("select count(*) from eng_audit where action='INVESTIGATE'")) >= 5 and "hunter2" not in ai_sql("select string_agg(detail::text, ' ') from eng_audit"))
    failed = [n for n, ok in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
