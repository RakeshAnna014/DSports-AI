# Offline scoring on the phone (cricket, gates 2f-2 to 2f-4)

What is saved on the phone, in what order, what happens to it when the server answers, the view the pad reads when there is no server, and the hook the screen
uses. The decisions are pure functions over plain data plus a small storage interface, so almost all of it is tested without a browser; the screen itself is
checked by the emulated browser run (`e2e/cricket-offline.mjs`) and the real-phone script 7c (docs/SETUP-AND-DEMO.md).
Design: [docs/plans/cricket-2f-offline-scoring.md](../../../../../docs/plans/cricket-2f-offline-scoring.md), sections 2 to 7.

```text
types.ts        QueueItem, QueueState, Mutation, Step, the cap (600) and the warning level (150)
queue.ts        the machine: tap, sendStarted, confirm, sendFailed, syncWithServer, previewRebase, rebase, keepTheirs, rejectedByServer, locked, forbidden,
                setAsideFrom, sendAgain, discard, setOfflineScoring, nextToSend
load.ts         loadQueue (rebuild from storage; nothing is dropped), newQueueState, saveEngineState
selfCheck.ts    engine version and fingerprint check that switches offline scoring on or off
exportText.ts   "Copy unsent balls": readable text and JSON of everything the phone holds
sender.ts       drain(): sends one item at a time, in order, and turns every server answer into one queue step
sendErrors.ts   what each HTTP answer means (and Retry-After, backoff)
httpSendApi.ts  the real network side (the only file that knows about HTTP)
engineStateApi.ts  reads the engine-state route's answer strictly
localView.ts    the scorer's view built from the phone's own log (a port of ScoringViewBuilder), proved equal to the Java view on the golden match
useOfflineScorer.ts  the hook: opens the store, rebuilds the queue, keeps the sender running (taps, online, visible, backoff), hands the screen the view and actions
UnsentSheets.tsx  the conflict, rejected, set-aside and copy/download sheets
phoneScript7c.ts  the balls of the real-phone script, checked against the doc
storage.ts      the QueueStorage interface, StorageError, and the in-memory adapter (tests)
idbStorage.ts   the IndexedDB adapter (works over plain http; no service worker, no secure-context API)
storageContract.ts  what every adapter must do; run against memory in Vitest and against real IndexedDB in Chrome by e2e/idb-contract.mjs
testHarness.ts  test only: a fake server with the real server's rules, and a phone that drives the queue
```

## How a step works

Every function takes the state and returns `{ state, mutations, refused }`. The caller commits `mutations` to storage in **one transaction** and only then adopts
`state` (so a ball is on the screen only after it is saved). If the commit fails nothing changed. A refused tap returns the old state and no mutations.

## The rules it keeps (tested in `queue.conservation.test.ts` after every step of random sequences)

1. An item leaves the queue only by a 200 from the server (`confirm`) or when the scorer discards a *set-aside* item by name (`discard`).
2. `enqueued = confirmed + discarded + items still held`, and the journal says the same.
3. The chain (waiting and in-flight items) is contiguous: item *i* expects the seq after the one before it. `expectedSeq` is the server's meaning: the last seq the scorer saw.
4. A tap the local engine refuses is never queued.
5. The queue is full at 600: the next tap is refused, nothing is dropped. A failed write refuses the tap and shows no ball.
6. A conflict (somebody else's entries are in) holds the whole chain until the scorer chooses: `rebase` (the balls that apply keep their ids and get new seqs; the
   server still validates each one) or `keepTheirs` (everything is set aside). Set-aside balls are saved, listed, exportable, and can be sent again or discarded.
7. Switching offline scoring off (version or fingerprint mismatch, the server refusing a ball the phone accepted) never touches an item: they stay and can still be sent.
8. Reload rebuilds the same state. An unreadable row, or a ball that no longer fits the saved log, becomes a set-aside item that keeps the raw data. Another
   account's balls are shown as set aside and left untouched in storage.

## Storage layout (IndexedDB database `dsports-scoring`, version 1)

`queue` (key `[matchId, n]`), `journal` (auto id, index on `matchId`), `engineState` (key `matchId`: setup, the server's log as of one seq, engine version and fingerprint),
`confirmed` (key `[matchId, seq]`: entries confirmed since the engine state was last saved, so a flush does not rewrite the whole log on every ball).

## Running the checks

```bash
cd frontend && pnpm exec vitest run src/features/cricket/offline     # the machine, the contract against memory
cd e2e && node idb-contract.mjs                                       # the same contract against real IndexedDB in Chrome, over plain http
```

`e2e/idb-contract.mjs` needs Chrome and the e2e `playwright` dependency; it is not part of the GitHub workflow (the workflow has no browser step yet).
