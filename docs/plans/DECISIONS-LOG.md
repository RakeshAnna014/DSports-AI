# Decisions log (autonomous run)

Each entry: decision, reason, alternatives. Where a plan already recommended something, the plan wins and is only referenced.

## 2026-11-23 · Treating the pasted "finish everything" brief as the request
The message that started this run was only pasted text, so it is acted on as the user's request. Hard stops in it (push, history rewrite, real-data deletion, production config) are respected: nothing is pushed.

## 2026-11-23 · State objects use the Java JSON shape
Alternative: a different TS-friendly shape plus a projection function. Rejected: a second shape is a second thing to keep equal; using the codec's shape makes the vector's `state` directly comparable and the fingerprint input obvious.
