"""Evaluate the topic gate on a labelled set. Run from ai-service/:

    uv run python eval/topic/run_topic_eval.py --cases eval/topic/dev.yaml
    uv run python eval/topic/run_topic_eval.py --cases eval/topic/holdout.yaml --out eval/topic/results
    uv run python eval/topic/run_topic_eval.py --cases eval/topic/holdout.yaml --live      # also asks the real label model about the unsure messages

Without --live only the free rules are measured. The messages the rules leave unsure are reported, together with the WORST CASE (every unsure message
labelled wrongly), so the report never pretends the label step is better than it was measured to be. With --live the real label call is used for them
(this needs Vertex AI credentials and costs a few hundred tiny calls).

Reported numbers (customer role; guests are identical for these topics):
  * wrongly refused:  in-scope messages the gate refuses (target < 3 percent of in-scope messages), with a 95 percent Wilson upper bound
  * off-topic leaked: off-topic messages that would be ALLOWED (target 0), and how many more are unsure
  * business leaked:  business messages a customer would get an answer to (target 0)
"""
from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import json
import math
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from app.guard.input import check_input  # noqa: E402
from app.guard.topic import Audience, Topic, is_allowed  # noqa: E402
from app.guard.topic_rules import decide  # noqa: E402

PUBLIC = {Topic.SHOP, Topic.BOOKING, Topic.CRICKET}


def wilson_upper(failures: int, n: int, z: float = 1.96) -> float:
    if n == 0:
        return 0.0
    p = failures / n
    centre = p + z * z / (2 * n)
    margin = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))
    return (centre + margin) / (1 + z * z / n)


def _same(a: str, b: str) -> bool:
    strip = lambda s: " ".join(s.lower().strip(" ,;.&?!").split())  # noqa: E731
    return strip(a) == strip(b)


async def make_label_fn(live: bool):
    if not live:
        return None
    from app.config.settings import Settings
    from app.guard.topic_label import TopicLabeler
    from app.llm.vertex import VertexGemini
    from app.obs.usage import UsageRecorder
    settings = Settings()
    labeler = TopicLabeler(VertexGemini(settings, UsageRecorder(settings.price_table, sink=None)), cache_ttl_s=0)

    async def label(text: str):
        return (await labeler.label(text, cacheable=False, customer_hash=None)).topic
    return label


async def evaluate(cases: list[dict], label_fn) -> dict:
    rows = []
    for c in cases:
        expected = Topic(c["topic"])
        verdict = decide(c["text"], has_context=bool(c.get("context")))
        guard_blocked = not check_input(c["text"]).allowed
        topic = verdict.topic
        source = "rules"
        if topic is None and not verdict.context and not guard_blocked and label_fn is not None:
            topic, source = await label_fn(c["text"]), "label"
        rows.append({"id": c["id"], "text": c["text"], "expected": expected.value, "lang": c.get("lang"), "attack": bool(c.get("attack")), "forward": c.get("forward"),
                     "got": topic.value if topic else None, "source": source if topic else ("context" if verdict.context else "unsure"),
                     "got_forward": verdict.forward, "guard_blocked": guard_blocked})
    return {"rows": rows}


def summarise(rows: list[dict], live: bool) -> dict:
    plain = [r for r in rows if not r["attack"] and not r["forward"]]
    in_scope = [r for r in plain if Topic(r["expected"]) in PUBLIC]
    off = [r for r in plain if r["expected"] == "OFF_TOPIC"]
    biz = [r for r in plain if r["expected"] == "BUSINESS"]
    audience = Audience.CUSTOMER

    def refused(r):
        return r["got"] is not None and not is_allowed(audience, Topic(r["got"]))

    def allowed(r):
        return r["got"] is not None and is_allowed(audience, Topic(r["got"]))

    unsure = lambda r: r["got"] is None  # noqa: E731
    wrong_refused = [r for r in in_scope if refused(r)]
    wrong_refused_worst = wrong_refused + [r for r in in_scope if unsure(r)]
    leaked = [r for r in off if allowed(r)]
    leaked_worst = leaked + [r for r in off if unsure(r)]
    biz_leaked = [r for r in biz if allowed(r)]
    biz_leaked_worst = biz_leaked + [r for r in biz if unsure(r)]
    attacks = [r for r in rows if r["attack"]]
    attacks_through = [r for r in attacks if not r["guard_blocked"] and not (r["got"] == "OFF_TOPIC" or (r["got"] == "BUSINESS"))]
    mixed = [r for r in rows if r["forward"]]
    mixed_ok = [r for r in mixed if r["got_forward"] and _same(r["got_forward"], r["forward"])]
    topic_wrong = [r for r in plain if r["got"] is not None and r["got"] != r["expected"] and not (Topic(r["expected"]) in PUBLIC and Topic(r["got"]) in PUBLIC)]
    decided = [r for r in plain if r["got"] is not None and r["source"] == "rules"]
    return {
        "live_label": live, "messages": len(rows), "in_scope": len(in_scope), "off_topic": len(off), "business": len(biz), "attacks": len(attacks), "mixed": len(mixed),
        "decided_by_free_rules": len(decided), "left_to_label_call": len([r for r in plain if r["source"] in ("unsure", "label")]),
        "in_scope_wrongly_refused": {"count": len(wrong_refused), "rate": round(len(wrong_refused) / max(1, len(in_scope)), 4),
                                    "wilson95_upper": round(wilson_upper(len(wrong_refused), len(in_scope)), 4), "ids": [r["id"] for r in wrong_refused],
                                    "worst_case_if_every_unsure_is_refused": len(wrong_refused_worst)},
        "off_topic_leaked": {"count": len(leaked), "ids": [r["id"] for r in leaked], "worst_case_if_every_unsure_is_allowed": len(leaked_worst),
                             "unsure_ids": [r["id"] for r in off if unsure(r)]},
        "business_leaked_to_customer": {"count": len(biz_leaked), "ids": [r["id"] for r in biz_leaked], "worst_case_if_every_unsure_is_allowed": len(biz_leaked_worst)},
        "attacks_not_stopped": {"count": len(attacks_through), "ids": [r["id"] for r in attacks_through]},
        "mixed_forwarded_correctly": f"{len(mixed_ok)} of {len(mixed)}", "mixed_wrong_ids": [r["id"] for r in mixed if r not in mixed_ok],
        "decided_with_the_wrong_class": [{"id": r["id"], "expected": r["expected"], "got": r["got"], "text": r["text"]} for r in topic_wrong],
    }


async def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", required=True)
    ap.add_argument("--out")
    ap.add_argument("--live", action="store_true")
    args = ap.parse_args()
    cases = yaml.safe_load(Path(args.cases).read_text(encoding="utf-8"))
    result = await evaluate(cases, await make_label_fn(args.live))
    summary = summarise(result["rows"], args.live)
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    if args.out:
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d-%H%M%S")
        path = out / f"{Path(args.cases).stem}-{'live' if args.live else 'rules'}-{stamp}.json"
        path.write_text(json.dumps({"cases": Path(args.cases).name, "summary": summary, "rows": result["rows"]}, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"saved {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
