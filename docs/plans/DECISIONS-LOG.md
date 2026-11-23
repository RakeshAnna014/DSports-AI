# Decisions log (autonomous run)

Each entry: decision, reason, alternatives. Where a plan already recommended something, the plan wins and is only referenced.

## 2026-11-23 · Treating the pasted "finish everything" brief as the request
The message that started this run was only pasted text, so it is acted on as the user's request. Hard stops in it (push, history rewrite, real-data deletion, production config) are respected: nothing is pushed.

## 2026-11-23 · State objects use the Java JSON shape
Alternative: a different TS-friendly shape plus a projection function. Rejected: a second shape is a second thing to keep equal; using the codec's shape makes the vector's `state` directly comparable and the fingerprint input obvious.

## 2026-11-23 · 2f-1: scope limits of the engine port
* **Super over, DLS:** not in the Java engine, so not in the TypeScript one (a tie is a result type; a revised target is a manual number, as ADR-019 says). Alternative: add a super over to both engines now. Rejected: it changes the rules and the vectors for a feature nobody asked for in 2f.
* **Request parsing:** `parseCommand` accepts only canonical lower-case UUIDs and whole numbers. The server also accepts unusual UUID spellings; the phone only ever sends the ids it was given, so being stricter loses nothing.
* **From-ts generator is not a port of Java's `RandomScorer`:** it leans towards commands the engine accepts so Java is asked to accept thousands of them, and keeps about a quarter junk. Alternative: a faithful port. Rejected: a faithful port would also share Java's bias towards refusals, which is the opposite of what the TypeScript to Java direction needs.
* **`logFrom` / `stepUndo` throw if a stored log no longer folds** (the Java `fold` does the same). 2f-2 must treat that as corrupted storage and park the queue, never drop it.
* **`frontend/tsconfig.tsbuildinfo` is tracked** and rewritten by every `tsc -b`. It is left out of this run's commits (restored with `git checkout`). Suggestion for the owner: `git rm --cached` it and add it to `.gitignore`; not done here because it is a repository-hygiene change nobody asked for.

## 2026-11-23 · 2f-2: queue design choices beyond the plan
* **Confirmed-tail store:** the plan said "after every confirmed answer the phone refreshes the stored state". Rewriting the whole log on every ball is quadratic over a 600-item flush, so a confirmed item appends one entry to a `confirmed` store and the full engine state is written only when the phone syncs with the server. Alternative: rewrite each time (simple, up to ~180 MB of writes in one flush). Rejected.
* **Storage returns the key with each row** (`StoredRow`), and `nextN` comes from keys, so an unreadable row can never be overwritten.
* **A chain that does not fit the server's log is a conflict**, even if nobody else's entries came in (the scorer decides; nothing is auto-dropped).
* **A tap while offline scoring is off is refused** (`OFFLINE_SCORING_OFF`); the screen (2f-4) falls back to one-ball-at-a-time online scoring. "Fix this ball" is therefore: set aside from that ball, then score it online.
* **Another account's balls** are shown as set aside in memory only (storage untouched), so they return to WAITING when their owner signs in again (D15).
* **The engine-state record is per match, not per user.** It holds the server's log of a match this phone was authorised to open; on a shared phone a second user's page load reads it only after the server has authorised them for that match. Alternative: key by user. Rejected: it would hide the first user's queue from the Review screen, which D15 wants shown as set aside.
* **The IndexedDB contract runs by hand / in e2e, not in the GitHub workflow**, because the workflow has no browser step. Alternative: add `playwright install chrome` to CI. Not done: it adds minutes and a dependency install to every push; revisit when 2f-4's browser e2e is added.

## 2026-11-23 · 2f-4: screen design choices
* **One route, two screens.** The previous scorer screen is kept whole as `LegacyScorerPage` and a gate chooses: offline screen when the phone can score offline, otherwise the previous screen. Alternative: edit the one screen to work both ways. Rejected: the previous screen has 90 tests of behaviour that must not change, and a browser without IndexedDB must behave exactly as before.
* **The tab lease is taken once, by the gate,** and passed down (two instances of the lease hook in one tab would contend).
* **An administrator's unlock never asks the scorer to decide.** It is an event with a seq but cannot change the score. Alternative (plan section 4 case A): treat it as a conflict. Changed because the real-stack run showed the scorer being asked about something they could do nothing with.
* **"Theirs" in the conflict sheet is whatever is not in this phone's own queue,** not "another account": the same account on another device is still somebody else's ball as far as this phone is concerned.
* **Names and the correction window are saved beside the engine state** (display only) so a page reloaded with no signal still shows names; they are merged, never replaced.
* **Browser scripts are not in CI** (no browser step in the workflow). Alternative: add a Chrome install step to the `frontend` job. Not done: minutes and a dependency install on every push, for tests that need the whole stack anyway.
