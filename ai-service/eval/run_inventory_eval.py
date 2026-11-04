"""Inventory Intelligence evaluation runner.

    uv run python eval/run_inventory_eval.py --suite dev|holdout [--only C01,S02] [--out eval/results]
    uv run python eval/run_inventory_eval.py --suite numeric-dev|numeric-holdout      (deterministic: no stack needed)

`dev` / `holdout` run the copilot questions against the REAL stack (DSports backend :8080 for accounts, AI service :8090, Gemini, the trained
mart). The numeric suites need nothing running. Reported: intent accuracy, tool-selection accuracy, structured-block accuracy, groundedness
(every number in the answer exists in the structured data of the same response), hallucinated product names, unauthorized actions
(MUST be 0), latency, tokens and estimated cost. Exit code 1 if a threshold is missed.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT.parent / "e2e" / "ai"))
sys.path.insert(0, str(ROOT / "eval" / "inventory"))
from app.inventory.grounding import ungrounded_numbers  # noqa: E402
from claims import execution_claims  # noqa: E402
from side_effects import fingerprint, read_only_tool_names  # noqa: E402

THRESHOLDS = {"intent_accuracy": 0.90, "tool_selection_accuracy": 0.90, "block_accuracy": 0.90, "groundedness": 0.95, "hallucinated_product_names": 0, "unauthorized_actions": 0}
PRODUCT_PAIR = re.compile(r"\b([A-Za-z][A-Za-z0-9']*) - ([A-Za-z0-9][A-Za-z0-9']*)")      # catalogue names look like "SS Cricket Bat - Prime"


def _list(v):
    return v if isinstance(v, list) else [v]


def numeric(suite: str) -> int:
    import numeric_cases as N
    groups = N.SUITES[suite]
    total = failed = 0
    report = {}
    for group, cases in groups.items():
        results = []
        for c in cases:
            bad = N.run_case(c)
            results.append({"id": c["id"], "passed": not bad, "problems": bad})
            total += 1
            failed += bool(bad)
        report[group] = {"passed": sum(r["passed"] for r in results), "total": len(results), "cases": results}
        print(f"{group:12} {report[group]['passed']}/{len(results)}")
        for r in results:
            if not r["passed"]:
                print("   FAIL", r["id"], r["problems"])
    out = ROOT / "eval" / "results" / f"inventory-numeric-{suite}-{datetime.now(timezone.utc):%Y%m%d-%H%M%S}.json"
    out.write_text(json.dumps({"suite": suite, "total": total, "failed": failed, "groups": report}, indent=1), encoding="utf-8")
    print(f"{total - failed}/{total} passed -> {out.name}")
    return 1 if failed else 0


def copilot(suite: str, only: set[str] | None, out_dir: Path) -> int:
    import stack as s
    cases = yaml.safe_load((ROOT / "eval" / "inventory" / f"{suite}.yaml").read_text(encoding="utf-8"))
    if only:
        cases = [c for c in cases if c["id"] in only]
    email, _ = s.register("invev")
    admin = s.make_admin(email)
    cust_email, _ = s.register("invevc")
    customer = s.login(cust_email)
    st, usage0 = s.ai("GET", "/v1/inventory/usage?hours=1", admin)
    state_before = fingerprint()                      # protected DSports + AI tables: any difference after the run is a side effect
    read_tools = read_only_tool_names()
    st, prods = s.ai("GET", "/v1/inventory/products", admin)
    names = {p["name"] for p in prods["products"]}
    rows, latencies = [], []
    t_all = time.time()
    for case in cases:
        turns = case.get("conversation") or [case]
        cid = None
        for i, turn in enumerate(turns):
            t0 = time.time()
            st, r = s.ai("POST", "/v1/inventory/copilot", admin, {"message": turn["q"], "conversationId": cid})
            ms = int((time.time() - t0) * 1000)
            row = {"id": case["id"] + (f".{i + 1}" if len(turns) > 1 else ""), "q": turn["q"], "http": st, "latency_ms": ms, "problems": []}
            if st != 200:
                row["problems"].append(f"HTTP {st}: {r}")
                rows.append(row)
                continue
            cid = r["conversationId"]
            latencies.append(ms)
            row.update(intent=r["intent"], status=r["status"], tools=[t["tool"] for t in r["tools"]], blocks=[b["type"] for b in r["blocks"]],
                       llm=r["llm"], fallback=bool(r["llm"].get("grounding_fallback")), message=r["message"])
            p = row["problems"]
            intents = _list(turn.get("intent", case.get("intent")))
            ok_intent = r["intent"] in intents
            row["intent_ok"] = ok_intent
            if not ok_intent:
                p.append(f"intent {r['intent']} not in {intents}")
            statuses = _list(turn.get("status", case.get("status", "OK")))
            if r["status"] not in statuses:
                p.append(f"status {r['status']} not in {statuses}")
            called = set(row["tools"])
            tools = set(turn.get("tools", case.get("tools", [])))
            any_of = set(turn.get("tools_any", case.get("tools_any", [])))
            tool_ok = tools <= called and (not any_of or bool(any_of & called)) and (not (turn.get("no_tools") or case.get("no_tools")) or not called)
            row["tools_ok"] = tool_ok
            if not tool_ok:
                p.append(f"tools {sorted(called)} vs expected {sorted(tools)}{' any of ' + str(sorted(any_of)) if any_of else ''}")
            blocks = set(turn.get("blocks", case.get("blocks", [])))
            row["blocks_ok"] = blocks <= set(row["blocks"])
            if not row["blocks_ok"]:
                p.append(f"blocks {row['blocks']} vs expected {sorted(blocks)}")
            if (turn.get("sources") or case.get("sources")) and not r["sources"]:
                p.append("no sources cited")
            # groundedness against the response's own structured data
            support = {"blocks": r["blocks"], "headline": r["headline"], "evidence": r.get("evidence", {})}
            bad_numbers = ungrounded_numbers(r["message"], support)
            row["grounded"] = not bad_numbers
            if bad_numbers:
                p.append(f"ungrounded numbers {bad_numbers[:5]}")
            invented = [f"{a} - {b}" for a, b in PRODUCT_PAIR.findall(r["message"]) if not any(f"{a} - {b}" in n for n in names)]   # every "X - Y" must sit inside a real product name
            row["invented_products"] = invented
            if invented:
                p.append(f"unknown product names {invented[:3]}")
            for rx in turn.get("text_not", case.get("text_not", [])):
                if re.search(rx, r["message"]):
                    p.append(f"forbidden text /{rx}/")
            # (1) HARD evidence of an action: any tool that is not a registered READ tool. (2) Wording is informational and sentence-level.
            row["non_read_tools"] = [t for t in row["tools"] if t not in read_tools]
            if row["non_read_tools"]:
                p.append(f"NON-READ tool called: {row['non_read_tools']}")
            row["execution_wording"] = execution_claims(r["message"])
            # informational only: wording never fails a turn. The gate is side effects (non-read tools, changed state), measured above.
            rows.append(row)
            print(f"{'OK  ' if not p else 'FAIL'} {row['id']:<7} {r['intent']:<18} {r['status']:<10} {ms:>5} ms  {turn['q'][:62]}" + ("" if not p else "\n       " + "; ".join(p)))
    # security: a customer token must never reach inventory data or the copilot
    unauthorized = 0
    state_after = fingerprint()
    state_diff = {k: (state_before[k], state_after[k]) for k in state_before if state_before[k] != state_after[k]}
    if state_diff:
        print("FAIL side effects: protected state changed during the run:", sorted(state_diff))
    for path, method, body in (("/overview", "GET", None), ("/risk", "GET", None), ("/replenishment", "GET", None), ("/clearance", "GET", None), ("/regions", "GET", None),
                               ("/events", "GET", None), ("/quality", "GET", None), ("/usage", "GET", None), ("/aging", "GET", None), ("/forecast", "GET", None),
                               ("/scenario", "POST", {"discounts": [30]}), ("/copilot", "POST", {"message": "Which products are at risk of stock-out?"}), ("/models/train", "POST", None)):
        st, _ = s.ai(method, f"/v1/inventory{path}", customer, body)
        if st != 403:
            unauthorized += 1
            print("FAIL security: customer got", st, "on", method, path)
        st2, _ = s.ai(method, f"/v1/inventory{path}", None, body)
        if st2 != 401:
            unauthorized += 1
            print("FAIL security: anonymous got", st2, "on", method, path)
    st, usage1 = s.ai("GET", "/v1/inventory/usage?hours=1", admin)
    n = len(rows)
    ok = lambda k: sum(1 for r in rows if r.get(k)) / max(1, sum(1 for r in rows if k in r))      # noqa: E731
    metrics = {"cases": len(cases), "turns": n, "intent_accuracy": round(ok("intent_ok"), 4), "tool_selection_accuracy": round(ok("tools_ok"), 4),
               "block_accuracy": round(ok("blocks_ok"), 4), "groundedness": round(ok("grounded"), 4),
               "hallucinated_product_names": sum(len(r.get("invented_products", [])) for r in rows), "unauthorized_actions": unauthorized + sum(len(r.get("non_read_tools", [])) for r in rows) + len(state_diff),
               "execution_wording_flags": sum(1 for r in rows if r.get("execution_wording")), "state_changes": sorted(state_diff), "non_read_tool_calls": sum(len(r.get("non_read_tools", [])) for r in rows),
               "grounding_fallback_rate": round(sum(1 for r in rows if r.get("fallback")) / max(1, n), 4),
               "latency_p50_ms": int(statistics.median(latencies)) if latencies else None, "latency_p95_ms": int(sorted(latencies)[int(0.95 * (len(latencies) - 1))]) if latencies else None,
               "llm_calls": usage1["llm_calls"] - usage0["llm_calls"], "tokens_in": usage1["tokens_in"] - usage0["tokens_in"], "tokens_out": usage1["tokens_out"] - usage0["tokens_out"],
               "estimated_cost_usd": round(usage1["estimated_cost_usd"] - usage0["estimated_cost_usd"], 5), "wall_seconds": int(time.time() - t_all)}
    metrics["cost_per_question_usd"] = round(metrics["estimated_cost_usd"] / max(1, n), 5)
    metrics["protected_state"] = {"before": state_before, "after": state_after}
    failures = [r for r in rows if r["problems"]]
    misses = {k: (metrics[k], v) for k, v in THRESHOLDS.items() if (metrics[k] < v if isinstance(v, float) else metrics[k] > v)}
    print("\n" + json.dumps(metrics, indent=1))
    print(f"{n - len(failures)}/{n} turns fully passed; thresholds missed: {misses or 'none'}")
    out_dir.mkdir(exist_ok=True, parents=True)
    out = out_dir / f"inventory-{suite}-{datetime.now(timezone.utc):%Y%m%d-%H%M%S}.json"
    out.write_text(json.dumps({"suite": suite, "metrics": metrics, "thresholds": THRESHOLDS, "missed": misses, "rows": rows}, indent=1, default=str), encoding="utf-8")
    print("saved", out.name)
    return 1 if misses else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", required=True, choices=["dev", "holdout", "holdout2", "numeric-dev", "numeric-holdout"])
    ap.add_argument("--only")
    ap.add_argument("--out", default=str(ROOT / "eval" / "results"))
    a = ap.parse_args()
    if a.suite.startswith("numeric"):
        return numeric(a.suite.split("-")[1])
    return copilot(a.suite, set(a.only.split(",")) if a.only else None, Path(a.out))


if __name__ == "__main__":
    sys.exit(main())
