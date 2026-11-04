"""Ask the inventory copilot one or more questions (same conversation) and print the answer plus grounding details.
Usage: python e2e/ai/inventory_ask.py "question 1" "question 2" ..."""
import sys

import stack as s

email, _ = s.register("invask")
admin = s.make_admin(email)
cid = None
for q in sys.argv[1:]:
    st, r = s.ai("POST", "/v1/inventory/copilot", admin, {"message": q, "conversationId": cid})
    if st != 200:
        print("!!", q, st, r)
        continue
    cid = r["conversationId"]
    print(f"\nQ: {q}\n   intent={r['intent']} status={r['status']} tools={[t['tool'] for t in r['tools']]}")
    print("   llm:", {k: v for k, v in r["llm"].items() if k in ("used", "grounding_fallback", "ungrounded", "error")})
    print("   headline:", r["headline"])
    print("   A:", r["message"].replace("\n", " | "))
