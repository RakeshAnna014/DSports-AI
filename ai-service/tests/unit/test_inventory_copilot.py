"""The inventory supervisor (LangGraph) with a stub repository and a scripted model: workflow, guards and failure behaviour. No network, no database."""
import uuid

import pytest

from app.auth.jwt import verify_token
from app.inventory.copilot import AskRequest, InventoryCopilot
from app.inventory.repository import InventoryUnavailable, Rows
from app.llm.base import LLMError
from tests.conftest import make_settings, make_token
from tests.fakes import FakeLLM
from tests.helpers import make_rig

ADMIN = verify_token(make_token(str(uuid.uuid4()), roles=("ADMIN",)), make_settings())


def _a(name, **kw):
    base = {"product_id": str(uuid.uuid4()), "name": name, "sku": "S", "sport": "Cricket", "category": "Bat", "status": "OK", "reason": None, "on_hand": 40, "incoming": 100,
            "lead_time_days": 10, "daily_demand": 8.0, "forecast_7": 56.0, "forecast_30": 240.0, "forecast_90": 720.0, "days_of_cover": 2.5, "stockout_date": "2026-10-10",
            "p_stockout_lt": 0.97, "safety_stock": 20, "reorder_point": 100, "risk_level": "HIGH", "reorder_qty": 55, "order_by": "2026-10-08", "arrival_date": "2026-10-18",
            "priority": "HIGH", "excess_units": 0, "excess_value": 0.0, "coverage_days": 5.0, "overstock_level": "NONE", "health_class": "Healthy", "age_days": 20,
            "supplier": "Meerut Sports Works", "moq": 12, "price": 7000.0, "cost": 4200.0,
            "detail": {"action": "ORDER", "demand_during_lead_time": 80.0, "target_level": 150, "incoming_arrives_after_stockout": True, "next_arrival_in_days": 12}}
    base.update(kw)
    return base


def _rows(items, total=None):
    r = Rows(items)
    r.total = total if total is not None else len(items)
    return r


SCENARIOS = {
    "duration_days": 14, "min_margin_pct": 10.0, "products": 3, "recommended_discount_pct": None, "recommendation_status": "AI Recommendation: nothing changed.",
    "scenarios": [
        {"discount_pct": 0.0, "units_sold": 100.0, "units_low": 100.0, "units_high": 100.0, "revenue": 100000.0, "margin": 40000.0, "ending_stock": 400.0, "inventory_reduction_units": 0.0,
         "inventory_reduction_value": 0.0, "products_at_stockout_risk": 0, "min_unit_margin_pct": 40.0, "net_benefit": 0.0},
        {"discount_pct": 30.0, "units_sold": 190.0, "units_low": 180.0, "units_high": 200.0, "revenue": 133000.0, "margin": 9000.0, "ending_stock": 310.0, "inventory_reduction_units": 90.0,
         "inventory_reduction_value": 54000.0, "products_at_stockout_risk": 0, "min_unit_margin_pct": 1.5, "net_benefit": 12000.0}],
    "selective": {"discounted_products": 1, "products": 3, "units_sold": 120.0, "revenue": 110000.0, "margin": 41000.0, "ending_stock": 380.0, "inventory_reduction_units": 20.0,
                  "inventory_reduction_value": 12000.0, "net_benefit": 3000.0, "discount_by_product": [{"name": "Test Jersey", "discount_pct": 30.0}]},
    "elasticity": [{"category": "Jersey", "elasticity": -2.0, "range": [-2.4, -1.6], "pull_forward": 0.1, "basis": "HISTORICAL", "n_promo_days": 500}],
    "assumptions": ["Demand response (1 - d) ** (-e), where e is the category's price elasticity"], "per_product_best": [], "items": {}, "data_source": "POC_SYNTHETIC",
}


class StubRepo:
    def __init__(self, fail_forecast=False):
        self.fail = fail_forecast
        self.calls = []

    async def taxonomy(self):
        return {"Cricket": ["Bat", "Gloves"], "Football": ["Jersey"]}

    async def resolve(self, **kw):
        return [{"product_id": "p1", "name": "Test Bat"}]

    async def run(self):
        return {"as_of": "2026-10-08", "created_at": "2026-10-08T00:00:00"}

    async def stock_risks(self, **kw):
        self.calls.append(("stock_risks", kw))
        return _rows([_a("Test Bat"), _a("Second Bat", days_of_cover=6.0, risk_level="MEDIUM")], total=19)

    async def sales_history(self, **kw):
        if self.fail:
            raise InventoryUnavailable("Insufficient data to produce a reliable forecast for that selection.")
        return {"units_recent": 400, "units_previous": 300, "change_pct": 33.3, "weekly": [], "days": 28}

    async def forecast_aggregate(self, **kw):
        if self.fail:
            raise InventoryUnavailable("Insufficient data to produce a reliable forecast for that selection.")
        return {"totals": {"7": 56.0, "30": 240.0, "90": 720.0}, "daily": [], "history": [], "model_name": "ridge_regression", "model_version": "1.0.0", "products": 2, "as_of": "2026-10-08"}

    async def events(self):
        return []

    async def scenario(self, **kw):
        self.calls.append(("scenario", kw))
        return dict(SCENARIOS)

    async def forecast(self, pid, **kw):
        raise InventoryUnavailable("Insufficient data to produce a reliable forecast for this product: only 20 days of history exist.")


async def make(llm=None, repo=None):
    rig = await make_rig(llm=llm)
    rig.services.inventory = repo or StubRepo()
    return rig, InventoryCopilot(rig.services)


async def ask(cop, text, cid=None, principal=ADMIN):
    return await cop.ask(principal, AskRequest(message=text, conversationId=cid))


async def test_stock_risk_workflow_uses_tools_and_reports_the_true_total():
    rig, cop = await make(FakeLLM(default="Test Bat has 2.5 days of cover against a 10 day lead time."))
    r = await ask(cop, "Which products are likely to stock out next month?")
    assert r.intent == "STOCK_RISK" and r.status == "OK" and [t["tool"] for t in r.tools] == ["get_stock_risk"]
    assert "19" in r.headline and "showing the top 2" in r.headline                  # "19 matching, 2 shown", never "2 products at risk"
    assert r.blocks[0]["type"] == "risk_table" and r.recommendationLabel and r.dataSource == "POC_SYNTHETIC"
    assert r.llm["used"] and not r.llm.get("grounding_fallback")
    assert rig.services.inventory.calls[0][1]["within_days"] == 30


async def test_the_model_cannot_introduce_a_number_the_tools_did_not_return():
    llm = FakeLLM(default="Test Bat has 9 days of cover and a 40% chance of a stock-out.")
    _, cop = await make(llm)
    r = await ask(cop, "Which products are at risk of stock-out?")
    assert r.llm["grounding_fallback"] and r.message.startswith(r.headline)             # replaced by the deterministic, verified text
    assert any("not found in the data" in w for w in r.warnings)


async def test_when_the_model_is_down_the_structured_answer_is_still_returned():
    _, cop = await make(FakeLLM(script=[LLMError("LLM_UNAVAILABLE", "down")]))
    r = await ask(cop, "Which products are at risk of stock-out?")
    assert r.status == "OK" and r.blocks and r.message.startswith(r.headline) and r.llm["used"] is False


async def test_the_prompt_carries_the_facts_and_the_no_calculation_rules():
    llm = FakeLLM(default="Fine.")
    _, cop = await make(llm)
    await ask(cop, "Which products are at risk of stock-out?")
    p = llm.prompts[-1]
    assert "Test Bat" in p["prompt"] and "high_risk_count" in p["prompt"] and p["tier"] == "reasoning"
    assert "Never calculate a new number" in p["system"] and "Never count or tally" in p["system"]


async def test_write_requests_are_refused_with_no_tool_call_and_no_model_call():
    llm = FakeLLM()
    rig, cop = await make(llm)
    for text in ("Create a purchase order for 100 cricket bats", "Change the price of the MRF cricket bat to 5000", "Launch a 30% promotion on jerseys"):
        r = await ask(cop, text)
        assert r.status == "REFUSED" and r.tools == [] and "can only recommend" in r.message
    assert llm.prompts == [] and rig.services.inventory.calls == []


async def test_a_scenario_wording_that_contradicts_the_engine_is_replaced():
    llm = FakeLLM(default="The AI recommends a 30% discount because it gives the best net benefit of 12000.")
    _, cop = await make(llm)
    r = await ask(cop, "What happens if we give 30% discount?")
    assert r.intent == "PROMOTION" and r.llm["grounding_fallback"]                      # the engine recommends NO uniform discount (margin floor)
    assert "margin floor" in r.headline and "selective" not in r.message.lower() or "individual best discount" in r.headline


async def test_a_consistent_scenario_explanation_is_kept():
    llm = FakeLLM(default="No single discount for all products keeps every one above the 10% margin floor; a selective plan gives a net benefit of 3000.")
    _, cop = await make(llm)
    r = await ask(cop, "What happens if we give 30% discount?")
    assert not r.llm.get("grounding_fallback") and "selective plan" in r.message


async def test_with_no_product_group_a_discount_defaults_to_the_slow_moving_stock_and_says_so():
    rig, cop = await make(FakeLLM())
    r = await ask(cop, "What happens if we give 30% discount?")
    assert rig.services.inventory.calls[-1][1]["health"] == ["Slow Moving", "Critical Aging"]
    assert r.headline.startswith("No product group was named") and "slow-moving" in r.headline
    r2 = await ask(cop, "What happens if we give 30% discount on all products?")
    assert rig.services.inventory.calls[-1][1]["health"] is None


async def test_insufficient_data_is_reported_as_such_never_filled_in():
    rig, cop = await make(FakeLLM(), StubRepo(fail_forecast=True))
    r = await ask(cop, "What is the demand forecast for cricket bats?")
    assert r.status == "INSUFFICIENT_DATA" and r.message.startswith("Insufficient data to produce a reliable forecast")
    assert r.blocks[0]["type"] == "notice" and r.recommendationLabel is None


async def test_why_keeps_the_previous_subject_and_looks_up_the_drivers():
    rig, cop = await make(FakeLLM(default="Cover is short."))
    first = await ask(cop, "Which cricket products are at risk of stock-out?")
    why = await ask(cop, "Why?", first.conversationId)
    assert why.intent == "STOCK_RISK" and why.llm["understanding"] == "followup"
    tools = {t["tool"] for t in why.tools}
    assert {"get_stock_risk", "get_sales_history", "get_demand_forecast"} <= tools
    assert rig.services.inventory.calls[-1][1]["sport"] == "Cricket"


async def test_a_customer_cannot_use_the_copilot_even_if_the_route_were_reachable():
    rig, cop = await make(FakeLLM())
    with pytest.raises(PermissionError):
        await ask(cop, "Which products are at risk of stock-out?", principal=rig.alice)
    assert rig.services.inventory.calls == []


async def test_a_conversation_cannot_be_read_by_another_user():
    rig, cop = await make(FakeLLM())
    first = await ask(cop, "Which cricket products are at risk of stock-out?")
    other = verify_token(make_token(str(uuid.uuid4()), roles=("ADMIN",)), make_settings())
    again = await ask(cop, "Why?", first.conversationId, principal=other)         # a different admin gets a fresh state, not the first one's context
    assert again.llm["understanding"] != "followup" and again.intent == "UNKNOWN"


async def test_unknown_questions_get_the_help_text_not_a_guess():
    llm = FakeLLM(script=['{"intent": "UNKNOWN", "sport": null, "category": null}'])
    _, cop = await make(llm)
    r = await ask(cop, "tell me a joke")
    assert r.status == "UNKNOWN" and "I can answer inventory questions" in r.message and r.tools == []
