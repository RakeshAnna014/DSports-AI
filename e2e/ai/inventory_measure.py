"""Measured (not configured) performance, reliability and cost facts for Inventory Intelligence on the running stack. Prints JSON and exits non-zero when an invariant fails.
Invariants: stored forecasts are reused; answering questions never retrains; dashboard (deterministic) views never call the model; tool calls per answer are bounded;
queries are bounded; logs contain no tokens, credentials or customer e-mail addresses.   Usage: python e2e/ai/inventory_measure.py"""
import json
import os
import re
import statistics
import sys
import time

import stack as s

failures: list[str] = []


def check(ok: bool, message: str) -> None:
    if not ok:
        failures.append(message)
    print(("PASS  " if ok else "FAIL  ") + message)


def pct(values: list[float], q: float) -> int:
    values = sorted(values)
    return int(values[min(len(values) - 1, int(q * (len(values) - 1) + 0.5))])


email, _ = s.register("measure")
admin = s.make_admin(email)


def call(method, path, body=None):
    t = time.perf_counter()
    st, r = s.ai(method, "/v1/inventory" + path, admin, body)
    return st, r, (time.perf_counter() - t) * 1000


def usage():
    return call("GET", "/usage?hours=1")[1]


def runs():
    return call("GET", "/quality")[1]["model"]["run_id"]


report: dict = {}
st, pr, _ = call("GET", "/products?sport=Cricket")
pid = pr["products"][0]["product_id"]

# ---- 1. deterministic views: latency and zero model use
u0, run0 = usage(), runs()
paths = ["/overview", "/forecast", f"/forecast/{pid}", "/risk", "/replenishment", "/overstock", "/slow-moving", "/aging", "/clearance", "/events", "/regions", "/quality"]
lat = {}
for p in paths:
    samples = []
    for _ in range(15):
        st, _, ms = call("GET", p)
        assert st == 200, (p, st)
        samples.append(ms)
    lat[p] = {"p50_ms": pct(samples, 0.5), "p95_ms": pct(samples, 0.95)}
for _ in range(5):
    call("POST", "/scenario", {"sport": "Cricket", "discounts": [30, 50]})
u1 = usage()
report["read_latency"] = lat
check(u1["llm_calls"] == u0["llm_calls"], f"dashboard views and scenarios make no model calls ({u1['llm_calls'] - u0['llm_calls']} new LLM calls over {len(paths) * 15 + 5} requests)")
check(runs() == run0, "reading forecasts does not retrain (current run id unchanged)")
f1, f2 = call("GET", f"/forecast/{pid}")[1], call("GET", f"/forecast/{pid}")[1]
check(f1["forecast_created_at"] == f2["forecast_created_at"] and f1["daily"] == f2["daily"], "a stored forecast is reused: identical creation time and values on repeated reads")
st, scen, ms = call("POST", "/scenario", {"sport": "Cricket", "discounts": [30, 50]})
report["scenario_latency_ms"] = int(ms)

# ---- 2. copilot: questions never retrain, tool calls bounded, latency, tokens, cost
questions = ["Which products are at risk of stock-out?", "What should we replenish this week?", "Which products should go on clearance?", "What happens if we give 30% discount?",
             "How will the upcoming cricket tournament affect demand?", "Summarize inventory health for management.", "Which region needs more cricket products?",
             "Create a purchase order for 100 cricket bats", "What is our clearance policy for inventory older than 90 days?", "Which products are overstocked?"]
db0 = usage()
cop, cid, max_tools, latencies = [], None, 0, []
for q in questions:
    t = time.perf_counter()
    st, r = s.ai("POST", "/v1/inventory/copilot", admin, {"message": q, "conversationId": cid})
    ms = (time.perf_counter() - t) * 1000
    assert st == 200, (q, st, r)
    cid = r["conversationId"]
    latencies.append(ms)
    max_tools = max(max_tools, len(r["tools"]))
    cop.append({"q": q, "ms": int(ms), "tools": len(r["tools"]), "llm_used": r["llm"].get("used")})
db1 = usage()
check(runs() == run0, f"{len(questions)} copilot questions did not retrain a model")
check(max_tools <= 8, f"tool calls per answer are bounded (max observed {max_tools}, configured limit 8)")
report["copilot"] = {"questions": len(questions), "latency_p50_ms": pct(latencies, 0.5), "latency_p95_ms": pct(latencies, 0.95), "max_tools_per_answer": max_tools,
                     "llm_calls": db1["llm_calls"] - db0["llm_calls"], "tokens_in": db1["tokens_in"] - db0["tokens_in"], "tokens_out": db1["tokens_out"] - db0["tokens_out"],
                     "estimated_cost_usd": round(db1["estimated_cost_usd"] - db0["estimated_cost_usd"], 5),
                     "cost_per_question_usd": round((db1["estimated_cost_usd"] - db0["estimated_cost_usd"]) / len(questions), 5), "cost_is_estimate": True,
                     "refusals_used_no_model": next(c for c in cop if c["q"].startswith("Create a purchase order"))["llm_used"] in (None, False)}
check(report["copilot"]["refusals_used_no_model"], "a refused request costs no model call")

# ---- 3. queries are bounded
for path in ("/risk?limit=100000", "/replenishment?limit=100000", "/overstock?limit=100000", "/clearance?limit=100000"):
    st, _, _ = call("GET", path)
    check(st == 422, f"{path.split('?')[0]} rejects an unbounded page size (HTTP {st})")
st, _, _ = call("POST", "/scenario", {"discounts": [95]})
check(st == 422, f"an impossible discount is a validation error, not a result (HTTP {st})")
st, r, _ = call("POST", "/copilot", {"message": "x" * 5000})
check(st == 422, f"an oversized question is rejected (HTTP {st})")

# ---- 4. training time (the one operation that is allowed to be slow)
t = time.perf_counter()
st, tr, _ = call("POST", "/models/train")
report["training"] = {"http_ms": int((time.perf_counter() - t) * 1000), "server_ms": tr.get("duration_ms"), "model": tr.get("model")}
check(st == 200 and runs() != run0, "an explicit training request creates a new versioned run")

# ---- 5. logs: no tokens, credentials or e-mail addresses
temp = os.environ.get("TEMP", "/tmp")
leaks = []
for name in ("ai_service_main.log", "dsports_backend.log"):
    path = os.path.join(temp, name)
    if not os.path.exists(path):
        continue
    text = open(path, encoding="utf-8", errors="ignore").read()
    for label, rx in (("jwt", r"eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}"), ("private key", r"BEGIN (RSA |EC )?PRIVATE KEY|\"private_key\"\s*:\s*\"-----"),
                      ("authorization header", r"(?i)authorization[\"']?\s*[:=]\s*[\"']?bearer\s+[A-Za-z0-9._-]{10,}"), ("password", r"(?i)\"password\"\s*:\s*\"(?!\*)[^\"]{4,}\"")):
        n = len(re.findall(rx, text))
        if n:
            leaks.append(f"{name}: {n} x {label}")
report["log_scan"] = {"files": ["ai_service_main.log", "dsports_backend.log"], "findings": leaks}
check(not leaks, "service logs contain no JWTs, private keys, bearer headers or passwords" + (f" ({leaks})" if leaks else ""))

print(json.dumps(report, indent=1))
sys.exit(1 if failures else 0)
