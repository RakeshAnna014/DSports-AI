# Cricket scoring engine vectors

Test vectors shared by the Java scoring engine (`backend/cricket`, the authority) and the TypeScript engine that the phone will use to keep scoring offline
(`frontend/src/features/cricket/engine`, step 2f). Each vector is a match setup and a list of steps with the answer the **Java engine gave**. Both
engines must reproduce every answer; the Java engine is the oracle, never the other way round.

Nothing in this folder is edited by hand. The Java build writes `golden/` and `random/`, the TypeScript build writes `from-ts/`, and a test on each side fails if
the committed files differ from what its engine produces now.

```text
manifest.json            vector format, engine version, the SHA-256 of every file, the vocabulary, and what cannot be covered (with the reason)
golden/*.json            hand-written scenarios, a full state recorded after every accepted step
random/seed-NNNN.json    seeded random matches (mostly sensible commands, many refused ones, some undos); a fingerprint after every accepted step
                         and a full state on every 25th one and the last
from-ts/seed-NNNN.json   the other direction: seeded random matches played through the TypeScript engine (its own generator, which leans towards commands the
                         engine accepts), with the TypeScript answer recorded; the Java engine replays them. Same step format as above.
from-ts/manifest.json    vector format, engine version, "generator": "typescript", and the SHA-256 of every file in from-ts/
```

## Regenerating, and what a reviewer looks at

```bash
cd backend
# 1. a rule changed an outcome: bump MatchScoring.ENGINE_VERSION (domain/scoring/MatchScoring.java)
# 2. regenerate
mvn -pl cricket -am test -Dtest=EngineVectorExporter -Dvectors.write=true -Dsurefire.failIfNoSpecifiedTests=false
# 3. read the diff of testdata/cricket-engine: it is the list of rules that changed. Commit it with the engine change.
# 4. update frontend/src/features/cricket/engine/vocabulary.ts if the manifest's vocabulary changed
```

```bash
cd frontend
# the TypeScript side (after a change to the TypeScript engine or to its generator); review the diff, then run the Java replay
pnpm run vectors:ts
cd ../backend && mvn -pl cricket test -Dtest=TsVectorsReplayTest
```

Files are LF only (`.gitattributes`); hashes are taken over the LF text.

## File shape

```json
{ "format": 1, "name": "…", "description": "…",
  "setup": { "overs": 2, "rules": { … }, "teamA": "<uuid>", "teamB": "<uuid>", "battingFirst": "<uuid>", "xiA": ["<uuid>", …], "xiB": [ … ] },
  "steps": [
    { "op": "command", "command": { "type": "DELIVER", "delivery": { "kind": "NORMAL", "runs": 1, "runsType": "BAT", "boundary": false } },
      "expect": { "result": "accepted", "seq": 2, "fp": "9f2c1d04a7be3c55", "status": "IN_PROGRESS", "next": "DELIVER", "end": null, "resultType": null } },
    { "op": "command", "command": { … }, "expect": { "result": "rejected", "violation": "NEEDS_NEW_BATTER" } },
    { "op": "undo", "mode": "BALL", "expect": { "result": "accepted", "seq": 9, "voids": [8, 7], "fp": "…", … } },
    { "op": "undo", "mode": "BALL", "expect": { "result": "nothing_to_undo" } }
  ] }
```

* `command` is exactly the body the phone sends to `POST /api/v1/matches/{id}/scoring/commands` (the `command` field), with unset fields left out.
  The Java tests read it back through the server's own request class, so a vector can only contain what the phone can send.
* The log is simulated as the server keeps it: every accepted command **and** every accepted undo takes the next `seq` (starting at 1). A refused command or
  an undo with nothing to undo writes nothing and changes nothing. An undo voids what `planUndo` says; the state is the engine's fold of the commands that
  no undo voids.
* `expect` after an accepted step: `seq` (the event just written), `voids` (undo only), `waivers` (rules set aside, only if any), `fp` (the fingerprint, below),
  `status` (match status), `next` (what the scorer must do: `START_INNINGS`, `SELECT_BATTER`, `SELECT_BOWLER`, `DELIVER` or `NONE`), `end` (end reason of the
  current innings or null), `resultType` (null until the match is complete) and, on some steps, `state`.
* After a refused step, `violation` is the rule code. The engine's message text is not part of the vector.

## State, canonical text and fingerprint

`state` is `{ "score": <the stored score>, "result": <the result or null>, "status": "<match status>" }`. `score` is the snapshot the server stores
(`ScoringJsonCodec.scoreJson`): `{ innings: [InningsState…], endedBy }` with every field of the innings records, so the TypeScript state types are a direct
port of those records.

* **Canonical text:** JSON with object keys sorted alphabetically at every level, no spaces, numbers as plain integers, ids as lower-case UUID text, other
  strings plain ASCII identifiers. Arrays keep their order.
* **Fingerprint:** the first 8 bytes of the SHA-256 of the canonical text of the state, as 16 hex characters. `frontend/.../engine/fingerprint.ts` computes it
  (with its own SHA-256, because `crypto.subtle` does not exist over plain http) and a test checks it against every `state` in every file.

The same fingerprint is what the phone will compare with the server's at run time (step 2f-3) to know that its engine and the server's agree.

## What the tests check

| Test | Where | Fails when |
|---|---|---|
| `EngineVectorsReplayTest` | Java | any step's answer differs from the file; a file's hash or the manifest's engine version is wrong |
| `EngineVectorsAreCurrentTest` | Java | the committed files differ from what the engine produces now (a rule changed without regenerating), or there is a stray file |
| `EngineVectorCoverageTest` | Java | a command type, rule, dismissal, delivery, end reason, waiver, undo mode, status or result type has no step (unless listed as not coverable with a reason) |
| `vectors.manifest.test.ts` | frontend | a file's hash differs, a file is missing or extra, the engine version is not one the phone supports, the vocabulary differs from the Java enums, or a state's fingerprint computed in TypeScript differs |
| `engine.vectors.test.ts` | frontend | the TypeScript engine's answer to any step of `golden/`, `random/` or `from-ts/` differs from the file (accepted or the rule that refused it, seq, voids, waivers, fingerprint, status, what is needed next, end reason, result type, the whole state where recorded) |
| `tsVectors.test.ts` | frontend | the committed `from-ts/` files differ from what the generator and the TypeScript engine produce now (byte for byte), or the set stops being varied (every command, dismissal and end reason accepted at least once) |
| `TsVectorsReplayTest` | Java | the TypeScript engine accepted something the Java engine refuses, or the reverse, or the two disagree on state, fingerprint or what comes next; a file's hash or the engine version is wrong |
| `engine.properties.test.ts`, `engine.rules.test.ts`, `engine.purity.test.ts` | frontend | undo does not restore the earlier fingerprint, a replay from scratch differs from the step-by-step state, a refusal changes something, a scorecard stops adding up, a rule scenario breaks, or the engine imports a clock, randomness, the network, storage or a UI library |

Both directions run on every push (see `.github/workflows/ci.yml`: `backend-unit` runs the Java tests including `TsVectorsReplayTest`; `frontend` runs the Vitest suite including the TypeScript replay).
