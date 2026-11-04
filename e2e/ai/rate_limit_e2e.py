"""Live check of the Java rate limiter against a backend started with LOW limits.

Start the backend like this (the local profile otherwise uses 100000, so nothing would ever be limited):
    RATE_LIMIT_AUTH_PER_MINUTE=6 RATE_LIMIT_TICKETS_PER_MINUTE=2 bash e2e/ai/start-backend.sh
Run:  python e2e/ai/rate_limit_e2e.py [base_url]        (default http://127.0.0.1:8080)
Then restart the backend WITHOUT those variables before running the other suites.
Throw-away users with random passwords are created; nothing is printed that is a credential.
"""
import json
import secrets
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
AUTH_LIMIT, TICKET_LIMIT = 6, 2
failures: list[str] = []


def call(method, path, body=None, token=None, headers=None):
    req = urllib.request.Request(BASE + path, method=method, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {}), **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.headers, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, e.headers, json.loads(raw or b"null")
        except ValueError:
            return e.code, e.headers, None


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))
    if not ok:
        failures.append(name)


password = "Aa1!" + secrets.token_urlsafe(12)
email_a, email_b = f"rl-a-{secrets.token_hex(4)}@example.com", f"rl-b-{secrets.token_hex(4)}@example.com"
case = {"category": "ORDER_ISSUE", "subject": "rate limit check", "description": "synthetic ticket created by rate_limit_e2e.py"}

# auth budget: register(1) login(2) register(3) login(4)
s, _, _ = call("POST", "/api/auth/register", {"email": email_a, "password": password, "firstName": "Rate", "lastName": "Limit"}); check("register A", s in (200, 201), s)
s, _, b = call("POST", "/api/auth/login", {"email": email_a, "password": password}); check("login A", s == 200, s)
tok_a = (b or {}).get("accessToken")
s, _, _ = call("POST", "/api/auth/register", {"email": email_b, "password": password, "firstName": "Rate", "lastName": "Limit"}); check("register B", s in (200, 201), s)
s, _, b = call("POST", "/api/auth/login", {"email": email_b, "password": password}); check("login B", s == 200, s)
tok_b = (b or {}).get("accessToken")

# ticket creation: limit per authenticated USER
s1, _, c1 = call("POST", "/api/v1/support/cases", case, tok_a, {"Idempotency-Key": secrets.token_hex(8)})
s2, _, c2 = call("POST", "/api/v1/support/cases", case, tok_a, {"Idempotency-Key": secrets.token_hex(8)})
if s1 not in (200, 201):
    print("INFO ticket create rejected:", json.dumps(c1)[:300])
s3, h3, b3 = call("POST", "/api/v1/support/cases", case, tok_a, {"Idempotency-Key": secrets.token_hex(8)})
check(f"ticket 1-{TICKET_LIMIT} accepted for user A", s1 in (200, 201) and s2 in (200, 201), (s1, s2))
check("ticket 3 for user A -> 429 with Retry-After and error body", s3 == 429 and h3.get("Retry-After") is not None and (b3 or {}).get("code") == "RATE_LIMITED", (s3, h3.get("Retry-After"), b3))
s, _, _ = call("POST", "/api/v1/support/cases", case, tok_b, {"Idempotency-Key": secrets.token_hex(8)}); check("same address, other user B is not blocked", s in (200, 201), s)
s, _, _ = call("POST", "/api/v1/support/cases", case); check("unauthenticated ticket POST is 401 (never reaches the ticket budget)", s == 401, s)

# messages: separate, 3x budget
ref = (c1 or {}).get("caseNumber")
if ref:
    codes = [call("POST", f"/api/v1/support/cases/{ref}/messages", {"body": f"msg {i}"}, tok_b)[0] for i in range(TICKET_LIMIT * 3 + 1)]
    print("INFO message codes for user B on a case it does not own (ownership answers first or budget applies):", codes)

# auth budget so far: 4 used. attempts 5 and 6 allowed, 7 limited
codes = [call("POST", "/api/auth/login", {"email": "nobody-" + secrets.token_hex(3) + "@example.com", "password": "wrong-Pass-1"})[0] for _ in range(AUTH_LIMIT - 4)]
check("remaining auth budget answers 401 (wrong credentials), not 429", all(c == 401 for c in codes), codes)
s, h, b = call("POST", "/api/auth/login", {"email": "x@example.com", "password": "wrong-Pass-1"})
check("login over the limit -> 429, Retry-After, RATE_LIMITED", s == 429 and h.get("Retry-After") is not None and (b or {}).get("code") == "RATE_LIMITED", (s, h.get("Retry-After")))
s, _, _ = call("POST", "/api/auth/register", {"email": f"rl-c-{secrets.token_hex(4)}@example.com", "password": password, "firstName": "R", "lastName": "L"}); check("register shares the auth budget -> 429", s == 429, s)
s, _, _ = call("POST", "/api/auth/refresh", {"refreshToken": "not-a-real-token"}); check("refresh shares the auth budget -> 429", s == 429, s)
s, _, _ = call("POST", "/api/auth/login", {"email": "x@example.com", "password": "wrong-Pass-1"}, headers={"X-Forwarded-For": "203.0.113." + str(secrets.randbelow(200) + 1)}); check("spoofed X-Forwarded-For cannot dodge the limit", s == 429, s)
s, _, _ = call("GET", "/api/catalog/products?size=1"); check("unrelated endpoints are not limited", s == 200, s)

print("RESULT:", "FAIL " + str(failures) if failures else "ALL PASS")
sys.exit(1 if failures else 0)
