"""Runs the Phase 2 business questions against the REAL stack (real Gemini, real mart) as an admin and prints what came back.
Usage: python e2e/ai/inventory_probe.py [--json out.json]     Needs: backend :8080 (for the admin token), AI service :8090, trained inventory run."""
import json
import sys
import time

import stack as s

QUESTIONS = [
    "Which products are at risk of stock-out?",
    "Why?",
    "Which products should we replenish this week?",
    "Which products are overstocked?",
    "Show me slow-moving products.",
    "Which products are older than 90 days?",
    "Which products should go on clearance?",
    "What happens if we give 30% discount?",
    "What happens if we give 50% discount?",
    "Which scenario is better?",
    "How will the upcoming cricket tournament affect demand?",
    "Do we have enough cricket inventory for the tournament?",
    "Which region needs more cricket products?",
    "Where should we move excess inventory?",
    "What are the top 10 inventory risks?",
    "Summarize inventory health for management.",
]


def main() -> int:
    email, _ = s.register("invprobe")
    admin = s.make_admin(email)
    cid, rows = None, []
    for q in QUESTIONS:
        t = time.time()
        st, r = s.ai("POST", "/v1/inventory/copilot", admin, {"message": q, "conversationId": cid})
        if st != 200:
            print(f"!! {q} -> {st} {r}")
            rows.append({"q": q, "status": st, "error": r})
            continue
        cid = r["conversationId"]
        print(f"\nQ: {q}\n   intent={r['intent']} status={r['status']} tools={[t_['tool'] for t_ in r['tools']]} llm={ {k: r['llm'].get(k) for k in ('used', 'tokensIn', 'tokensOut', 'grounding_fallback')} } {int((time.time() - t) * 1000)}ms")
        print("   A:", r["message"].replace("\n", " | ")[:420])
        rows.append({"q": q, **{k: r[k] for k in ("intent", "status", "message", "headline", "tools", "warnings", "llm")}, "blocks": [b["type"] for b in r["blocks"]]})
    if "--json" in sys.argv:
        json.dump(rows, open(sys.argv[sys.argv.index("--json") + 1], "w"), indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
