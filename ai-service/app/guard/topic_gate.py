"""The topic gate: free rules first, a label-only model call only when the rules are unsure, and the role policy applied in code.

Nothing here reads a system prompt to decide who may see what: the audience comes from the verified principal (``audience_of``), the policy from
``ALLOWED``. A refusal is a fixed template, so it costs no model call. ``GateDecision`` is what the agent graph acts on.
"""
from __future__ import annotations

from dataclasses import dataclass

from app.guard import topic_templates as templates
from app.guard.topic import Audience, Topic, is_allowed
from app.guard.topic_label import LabelResult, TopicLabeler
from app.guard.topic_rules import decide


@dataclass(frozen=True)
class GateDecision:
    allowed: bool
    topic: Topic | None                          # None: allowed only because the conversation has live context
    stage: str                                   # rules | context | cache | model | invalid | error
    template: templates.Template | None = None   # set when the reply is a fixed template instead of the normal pipeline
    forward: str | None = None                   # a mixed message: the in-scope part, to answer normally (a decline sentence is appended)
    label: LabelResult | None = None             # the label call, if one was made (for usage accounting and metrics)
    rule: str | None = None                      # which rule decided, when the rules did

    @property
    def mixed(self) -> bool:
        return self.forward is not None

    @property
    def model_calls(self) -> int:
        return 1 if self.label is not None and self.label.called_model else 0


class TopicGate:
    def __init__(self, labeler: TopicLabeler):
        self._labeler = labeler

    async def check(self, message: str, *, audience: Audience, has_context: bool, customer_hash: str | None) -> GateDecision:
        verdict = decide(message, has_context=has_context)
        if verdict.topic is not None:
            return self._apply(verdict.topic, audience, "rules", forward=verdict.forward, rule=verdict.rule)
        if verdict.context:
            return GateDecision(True, None, "context")
        label = await self._labeler.label(message, cacheable=not has_context, customer_hash=customer_hash)
        if label.topic is None:
            template = templates.UNAVAILABLE if label.source == "error" else templates.UNSURE      # model down -> "unavailable"; model answered badly -> "rephrase"
            return GateDecision(False, None, label.source, template=template, label=label)
        return self._apply(label.topic, audience, label.source, label=label)

    @staticmethod
    def _apply(topic: Topic, audience: Audience, stage: str, *, forward: str | None = None, label: LabelResult | None = None, rule: str | None = None) -> GateDecision:
        if not is_allowed(audience, topic):
            return GateDecision(False, topic, stage, template=templates.refusal(topic, audience), label=label, rule=rule)
        return GateDecision(True, topic, stage, forward=forward, label=label, rule=rule)
