# Phase 3 support evaluation: how to read the result files

Written 2026-10-13 while documenting the project. The JSON files are preserved exactly as the runner wrote them; this note explains what their `label` field does and does not mean.

The runner (`eval/run_support_eval.py`) labels a holdout run `FIRST RUN (unseen)` when it does **not** find a preserved first-run file in the *output directory it was given*, and `regression (NOT unseen: the first run is preserved)` when it does.

Three result files copied here from re-runs carry the label `FIRST RUN (unseen)` **although they are not first runs**, because those re-runs wrote to a temporary output directory that had no preserved first-run file:

| File | Actual status | Label inside the JSON |
|---|---|---|
| `support-holdout-REGRESSION-quota-retry-H01-H04-H07-H11-H14-H15-20261013.json` | regression re-run (6 cases, 2026-10-13), after fixes made following the first run | `FIRST RUN (unseen)` (incorrect) |
| `support-holdout-REGRESSION-full-final-code-20261013.json` | regression run on the final code (23/24) | `FIRST RUN (unseen)` (incorrect) |
| `support-holdout-REGRESSION-final-code-20261013.json` | regression run on the final code (23/24) | `FIRST RUN (unseen)` (incorrect) |

The genuine first execution of the held-out set is the composite recorded in `support-holdout-first-run-PARTIAL-crashed.log/.err` (H01-H14, log lines only), `support-holdout-first-run-PARTIAL-H15-H21.log/.err` (H15-H21) and `support-holdout-20261012-194220.json` (H22-H24; its own label says `regression` only because of a file-name match, see `docs/ai/phase3/10-evaluation.md`): 15 of 24 passed.

Everything run after code was changed in response to those failures is a **regression** result, not an unseen one, whatever its label says.

`support-holdout-20261012-195623.json` (17/24) was produced while Gemini quota was exhausted (`llm_calls = 0`, no tokens): its `OUT_OF_SCOPE` failures are environmental.

Dataset hashes: every holdout result file records `dataset_sha256 = 57b2ab2a...`; `support-holdout.sha256` records `96d06bae...`, taken before harness-only `copies` entries were added to `eval/support/holdout.yaml`. The file is untracked in git, so the hash does not by itself prove the expectations are unchanged.
