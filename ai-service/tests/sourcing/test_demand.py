from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.sourcing import demand as d

VOCAB = ["MRF Cricket Bat - Virgin", "Yonex", "Rackets", "Badminton", "Table Tennis Bat", "Football"]
PRODUCTS = [{"name": "Stiga Table Tennis Bat", "brand": "Stiga", "category": "Bats", "sport": "Table Tennis"}]


def test_redaction_removes_pii_and_filler():
    assert d.redact_query("Do you sell pickleball paddles?") == "pickleball paddles"
    assert d.redact_query("mail me at a@b.com about 9876543210 pickleball paddle") == "mail about pickleball paddle"
    assert d.redact_query("a") is None and d.redact_query("") is None and d.redact_query(None) is None
    assert len(d.redact_query("word " * 60) or "") <= 80


def test_distinctive_terms_skip_generic_wording_and_short_words():
    assert d.distinctive_terms("lightweight carbon pickleball paddle") == ["pickleball", "paddle"]
    assert d.distinctive_terms("best cheap beginner bat") == []


def test_a_term_unknown_to_results_and_catalogue_is_unmet_but_known_words_are_not():
    assert d.unmatched_terms("pickleball paddles", PRODUCTS, VOCAB) == ["pickleball", "paddles"]    # neither word is in the results or the catalogue vocabulary
    assert d.unmatched_terms("table tennis bats", PRODUCTS, VOCAB) == []
    assert d.unmatched_terms("badminton rackets", PRODUCTS, VOCAB) == []                       # known to the catalogue vocabulary even though not in these results


class FakeRepo:
    def __init__(self):
        self.rows = []

    async def record_demand(self, *a):
        self.rows.append(a)


def services(repo, vocab=VOCAB):
    async def catalogue_rows():
        return [{"name": v, "brand": "", "category": "", "sport": ""} for v in vocab]
    return SimpleNamespace(sourcing=SimpleNamespace(repo=repo, catalogue_rows=catalogue_rows))


P = SimpleNamespace(customer_hash="h1")


@pytest.mark.asyncio
async def test_unmet_term_among_related_results_is_recorded_as_low_results():
    repo = FakeRepo()
    await d.record_search_outcome(services(repo), P, {"query": "do you sell pickleball paddles"}, PRODUCTS)
    assert repo.rows == [("h1", "pickleball paddles", None, 1, "LOW_RESULTS", "pickleball")]      # the longest unknown term identifies the request across wordings


@pytest.mark.asyncio
async def test_the_customers_message_is_the_fallback_when_the_extractor_kept_no_query():
    repo = FakeRepo()
    await d.record_search_outcome(services(repo), P, {"category": "Bat"}, PRODUCTS, "show me pickleball paddles")
    assert repo.rows[0][1] == "pickleball paddles"
    repo2 = FakeRepo()
    await d.record_search_outcome(services(repo2), P, {"category": "Bat"}, PRODUCTS, "which one is lighter")        # a follow-up, not a missing product\n    repo3 = FakeRepo()\n    await d.record_search_outcome(services(repo3), P, {"category": "Bat"}, PRODUCTS, "tell me about the pickleball paddle price")\n    assert repo3.rows == []      # not phrased as a request for a product
    assert repo2.rows == []


@pytest.mark.asyncio
async def test_no_products_is_recorded_as_zero_results():
    repo = FakeRepo()
    await d.record_search_outcome(services(repo), P, {"query": "squash goggles", "sport": "Squash"}, [])
    assert repo.rows[0][4] == "ZERO_RESULTS" and repo.rows[0][2] == "Squash" and repo.rows[0][5] == "goggles"      # key term: the longest distinctive word


@pytest.mark.asyncio
@pytest.mark.parametrize("slots,products", [
    ({"query": "table tennis bat"}, PRODUCTS),                       # satisfied
    ({"query": "pickleball paddle", "max_price": 500}, PRODUCTS),    # a budget filter, not a missing product
    ({"query": "pickleball paddle", "skill_level": "beginner"}, []),  # a narrow filter
    ({"query": "best cheap bat"}, PRODUCTS),                         # only generic wording
    ({"query": None}, []), ({}, [])])
async def test_not_recorded(slots, products):
    repo = FakeRepo()
    await d.record_search_outcome(services(repo), P, slots, products)
    assert repo.rows == []


@pytest.mark.asyncio
async def test_guests_and_failures_never_break_the_answer():
    repo = FakeRepo()
    await d.record_search_outcome(services(repo), None, {"query": "pickleball paddle"}, [])
    assert repo.rows == []
    broken = SimpleNamespace(sourcing=SimpleNamespace(repo=repo, catalogue_rows=None))
    await d.record_search_outcome(broken, P, {"query": "pickleball paddle"}, PRODUCTS)          # catalogue unreadable: no signal, no exception
    assert repo.rows == []
    await d.record_search_outcome(SimpleNamespace(sourcing=None), P, {"query": "x y z w"}, [])
