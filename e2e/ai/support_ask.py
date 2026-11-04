"""Talk to the REAL customer assistant as a real customer and print what came back.

  python e2e/ai/support_ask.py <email> "message" ["next message" ...]   [--confirm]   [--password P]

Each message goes to POST /v1/chat (through the same service the web app uses). With --confirm, a pending confirmation is confirmed after the turn that proposed it.
"""
import json
import sys

import stack as s


def main() -> None:
    args = sys.argv[1:]
    confirm = "--confirm" in args
    args = [a for a in args if a != "--confirm"]
    pw = s.PASSWORD
    if "--password" in args:
        i = args.index("--password"); pw = args[i + 1]; del args[i:i + 2]
    email, messages = args[0], args[1:]
    code, body = s.dsports("POST", "/api/auth/login", body={"email": email, "password": pw}); assert code == 200, (code, body)
    token, conv = body["accessToken"], None
    for m in messages:
        code, r = s.ai("POST", "/v1/chat", token, {"message": m, "conversationId": conv}); conv = r.get("conversationId", conv)
        print(f"\n>>> {m}\n[{code} {r.get('intent')} / {r.get('type')} / tools={[t['tool'] for t in r.get('meta', {}).get('tools', [])]}]")
        print(r.get("message"))
        if r.get("support"):
            print("support:", json.dumps({k: v for k, v in r["support"].items() if k in ("kind", "complaint", "resolution", "outcome")}, default=str)[:400])
        if confirm and r.get("requiresConfirmation"):
            code, c = s.ai("POST", "/v1/confirm", token, {"confirmationToken": r["confirmationToken"], "conversationId": conv})
            print(f"\n[confirm {code} {c.get('type')}] {c.get('message')}")


if __name__ == "__main__":
    main()
