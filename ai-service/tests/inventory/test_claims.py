"""Regression tests for the execution-claim wording check (the first inventory holdout run flagged a disclaimer as a claim)."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "eval" / "inventory"))
from claims import execution_claims  # noqa: E402


@pytest.mark.parametrize("text", [
    "No changes were executed.",                                    # the sentence reproduced in 3 of 20 reruns of HC07
    "Nothing was executed.",
    "Nothing has been executed.",
    "The AI Recommendation is decision support only; nothing was changed.",
    "No purchase order was created.",
    "No price was changed and no promotion was launched.",
    "Prices were not updated.",
    "I can't change prices or create purchase orders.",
    "I have not placed any order.",
    "A 30% discount would be applied by the pricing engine only after approval.",
    "This is an AI Recommendation; the transfer is a suggestion and has not been raised.",
    "The scenario assumes a 20% discount for 14 days.",
    "A purchase order quantity is raised to the MOQ even if the calculated need is smaller.",      # a policy sentence describing a rule (dev run, 2026-10-09)
    "An inter-region transfer takes about 5 days and is raised by the warehouse operations team.",  # holdout v2 HX13
    "Purchase orders are raised by the buying team.",
])
def test_disclaimers_and_hypotheticals_are_not_claims(text):
    assert execution_claims(text) == []


@pytest.mark.parametrize("text", [
    "I have created the purchase order for 100 bats.",
    "The price of the MRF bat was changed to 5000.",
    "We've placed an order for 200 running shoes.",
    "The 30% promotion has been launched on all jerseys.",
    "Stock was transferred to the East warehouse.",
    "The discount is now activated.",
    "I successfully executed the transfer.",
])
def test_real_execution_claims_are_found(text):
    assert execution_claims(text) == [text]


def test_a_claim_hidden_in_a_longer_answer_is_still_found_and_the_disclaimer_next_to_it_is_not():
    answer = "A 30% discount sells 788 units. No changes were executed. I have launched the promotion. This is POC synthetic data."
    assert execution_claims(answer) == ["I have launched the promotion."]
