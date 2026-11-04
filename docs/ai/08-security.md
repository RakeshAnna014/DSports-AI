# 08 — Security

## Principles

1. **Java is the authority.** The AI service forwards the *customer's own* bearer token to DSports for every account call, so the
   backend's ownership checks (`403`/`404`) decide what is visible. The AI service is never more privileged than the customer and has
   no service credential. No customer id is ever a tool argument.
2. **The model decides nothing authoritative.** Price, stock, order/payment/refund/return status, eligibility and ownership come from
   tool results; the model may only explain them, and its text is verified before it is returned.
3. **Data is not instructions.** Retrieved documents, reviews, free text from the data store and customer messages are sanitised,
   fenced and labelled untrusted; the system prompt is fixed and separate.
4. **Anything that changes state needs the customer's confirmation**, is re-validated, idempotent and audited.

## Authentication

HS256/HS384/HS512 bearer tokens signed with the shared `JWT_SECRET` (the Java backend picks the strength from the secret length: a
56-byte local secret produced **HS384**, which a first HS256-only version of this service rejected — found against the real backend,
now covered). `exp` and `sub` (a UUID) are required. `none`, RS*/ES*, tampered, expired and malformed tokens are `401`; a present but
non-Bearer `Authorization` header is a `401`, never a silent downgrade to guest. Identity (`sub`) is hashed with
`HMAC-SHA256(AI_CUSTOMER_HASH_KEY, id)`; the raw id is never logged, stored or returned. Guests may use product discovery, product
knowledge and policy answers only.

## Input guard (`guard/input.py`) — before any model or tool

Fixed refusals, no model call, for: instruction override / jailbreak phrasing, system-prompt extraction, credential/secret requests,
cross-customer requests ("another customer's orders", "list all orders", "customer id …"), privilege claims ("I am the admin"),
oversized or empty messages. These are a first line, not the only one: even a request that slips past cannot read another
customer's data, because the tools carry only the caller's token.

## Untrusted data (`guard/untrusted.py`)

Instruction-like sentences (override, role-play, extraction, credential, exfiltration, tool-abuse, fence-escape, cross-customer
patterns) are removed from retrieved chunks, review text and refund reasons while preserving structure; remaining text is wrapped in
`<untrusted_data>` and the closing tag cannot be forged. Tested with a poisoned document through the real pipeline.

## Output grounding (`guard/grounding.py`)

Before a model-written answer is returned it is checked against this turn's tool results: every money amount, every order/shipment/
tracking/payment reference, every known product name (and brand-bearing bold span), every status claim (delivered, in transit,
refund completed, approved/rejected, out of stock, cancelled) and the return-eligibility verdict. Retrieval metadata and document
text are **not** evidence for customer-specific claims (a policy that contains the word "COMPLETED" cannot make "your refund
completed" pass). Conditional policy wording ("once approved …") is not treated as a claim. On any violation the model text is replaced
by the deterministic answer built from the same data. Also dropped: any answer that repeats the system prompt id. Opinion text is allowed
and labelled "AI explanation" in the UI. The evaluation re-checks every delivered answer with this module independently of the
service's own pass.

## Confirmation tokens (`guard/confirmation.py`)

`v1.<payload>.<HMAC-SHA256>` with payload `{nonce, customer hash, tool, args hash, expiry}`, signed with a sub-key derived from
`AI_CUSTOMER_HASH_KEY`. Customer-bound, tool-bound, argument-bound (arguments are stored server-side and never resent), single-use
(an atomic `PENDING → CONSUMED` update; verified with 12 concurrent redemptions → exactly one), valid for `AI_CONFIRMATION_TTL_S`
(300 s). A token cannot be edited, used by another customer, replayed, or used after expiry; all failures collapse into
`CONFIRMATION_INVALID`/`EXPIRED`/`ALREADY_USED` so a prober learns nothing. At confirmation the proposal is recomputed from live data
and compared (arguments **and** the summary the customer saw, which carries price/quantity); a mismatch → `STATE_CHANGED`/`NOT_ELIGIBLE`
and nothing executes. The write carries `Idempotency-Key: ai-<nonce>`.

## Audit (`dsports_ai.audit`)

PROPOSED, CONFIRMED, REVALIDATION_FAILED, EXECUTED, EXECUTION_FAILED, CONFIRM_REJECTED (with reason), CANCELLED — each with the
customer hash, tool, argument hash, correlation id and conversation id. Java keeps its own records of returns.

## Personal data

Sent to the model: first name is never sent; product facts, order numbers, item names/prices/statuses, tracking number and courier,
return eligibility. **Not** sent or returned: addresses, phone, e-mail, user ids, payment instrument data (tool outputs are
whitelists; tested). Logs are JSON with redaction of JWTs, bearer values, e-mails, connection strings, private keys, raw UUIDs and
any field named like a secret or personal datum; metrics carry no customer labels.

## Other controls

Rate limit per customer (`AI_RATE_LIMIT_PER_MIN`, 30), request/token/tool budgets, message length limit, strict validation (422
without echoing input), non-root container, secrets only via environment, credential file mounted read-only and never read by our
code, MCP Host allow-list, one error contract with no internals.

## Known limitations

* Access tokens live in browser `localStorage` (pre-existing); an XSS bug would expose them to the assistant UI too. The assistant UI
  renders model text as plain text (bold only), never HTML.
* The shared HMAC secret is held by two runtimes.
* The input patterns are English-only heuristics; the structural controls (token-bound identity, confirmation, grounding) do not
  depend on them.
* Prompt-injection resistance is evidence-based, not proven: see the evaluation's security cases.
* Rate limiting and caches are in-process (single instance in Phase 1).
