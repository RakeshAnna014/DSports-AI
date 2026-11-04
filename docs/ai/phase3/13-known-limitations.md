# 13. Known limitations

Stated plainly; none are hidden by the tests.

1. **No automatic resolution.** The assistant cannot resolve, refund, replace or cancel. `automaticallyResolved` is 0 by design.
2. **No image or photo assessment.** Damage evidence is the customer's description only.
3. **No carrier, warehouse or proof-of-delivery data** exists in DSports. "Delivered but not received" is recorded and escalated, not verified.
4. **No live agent integration.** "Human" means a support executive working the case in the console; there is no chat handover, and the assistant says so.
5. **No official SLA or policy.** Sample documents and thresholds (stall days, reopen window, high-value threshold) are demo configuration, labelled as such.
6. **A true double charge cannot occur** with the current schema (`uq_payments_order_open`); live evaluations use "two attempts, one captured". The confirmed-duplicate branch is covered by unit tests only.
7. **Exchanges are not a DSports capability**; the assistant explains this and offers a return/case.
8. **Deterministic customer text.** Investigation wording is composed from facts, not written by a model; it is accurate but less fluent. An LLM-written explanation layer was not added.
9. **Pattern-based guard.** The input guard can miss novel phrasings (three were found in the held-out run and then patched). Defence in depth (tools, backend, SQL) limits impact; this remains the weakest layer.
10. **English only; text only; no notifications** (email/SMS) when staff reply.
11. **Evaluation scale.** 45 development and 24 held-out cases against real data and a real model; the held-out set was used for fixes after its first run, so later held-out runs are regression, not unseen (doc 10). Several first-run results of cases H01–H14 survive only as log lines.
12. **Backend background cancellation** of unpaid orders (~30 minutes) can invalidate long-running evaluation worlds; the evaluator fingerprints state per customer to avoid blaming the assistant.
13. **Validation was not one clean run.** The consolidated regression recorded 6 failed steps (quota, a script error of mine, an obsolete Phase 1 expectation, transient browser timeouts); each was re-run and passed, but there is no single uninterrupted green `validate-all` run (doc 14 §10).
14. **Held-out hash mismatch.** `holdout.yaml` no longer matches its recorded sha256 (harness `copies` entries were added later) and is not under version control.
15. **Phase 1 case F06** was changed on purpose (the old "can't create a ticket" answer no longer exists); S09's one-off `PermissionError` has no identified cause.
16. **Long evaluations can fail with 401** because the 15-minute access token expires; Gemini quota or timeouts invalidate runs.
17. **Inherited:** HS256 shared secret and browser-stored tokens (Phase 1); no CI pipeline.
