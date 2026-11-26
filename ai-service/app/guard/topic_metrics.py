"""Metrics for the topic gate, and the honest estimate of the tokens it saves.

A refused message skips the Tier 1 classifier (and everything after it). The saving is therefore estimated as the classifier's average measured token usage
(an exponential moving average of real calls in this process), minus the tokens the label call itself cost when it was needed. Before any classifier call has
been measured, a fallback derived from the size of the classifier prompt is used. It is an ESTIMATE: it ignores the explainer calls a mis-routed off-topic
message might also have triggered, so it errs low, and it is labelled as an estimate everywhere it is shown.
"""
from __future__ import annotations

from app.guard.topic import Audience
from app.guard.topic_gate import GateDecision
from app.obs import metrics
from app.router import tier1

_ALPHA = 0.2


class ClassifierUsage:
    def __init__(self) -> None:
        self._avg: float | None = None

    def observe(self, tokens_in: int, tokens_out: int) -> None:
        total = float(tokens_in + tokens_out)
        if total > 0:
            self._avg = total if self._avg is None else (1 - _ALPHA) * self._avg + _ALPHA * total

    def average(self) -> int:
        if self._avg is not None:
            return round(self._avg)
        return len(tier1.SYSTEM) // 4 + 120 + 60        # prompt + a typical short message and context + a typical JSON answer (fallback until measured)


CLASSIFIER_USAGE = ClassifierUsage()


def record(decision: GateDecision, audience: Audience) -> None:
    label = decision.label
    if label is not None:
        metrics.TOPIC_LABEL_CALLS.labels(label.source).inc()
    outcome = "mixed" if decision.mixed else ("allowed" if decision.allowed else ("unsure" if decision.topic is None else "refused"))
    metrics.TOPIC_DECISIONS.labels(audience.value, decision.stage, outcome).inc()
    if outcome == "refused" and decision.topic is not None:
        metrics.TOPIC_REFUSALS.labels(decision.topic.value, audience.value).inc()
    if outcome in ("refused", "unsure"):
        spent = (label.tokens_in + label.tokens_out) if label is not None else 0
        saved = CLASSIFIER_USAGE.average() - spent
        if outcome == "refused" and saved > 0:
            metrics.TOPIC_TOKENS_SAVED.labels(audience.value).inc(saved)
