"""The topic evaluation runner. It is exercised on the DEV set only: the held-out set is run by hand, once per code state, and never by the test suite."""
import asyncio
import importlib.util
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("run_topic_eval", ROOT / "eval" / "topic" / "run_topic_eval.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def test_wilson_upper_bound_is_honest_about_small_samples():
    assert runner.wilson_upper(0, 0) == 0.0
    assert 0.05 < runner.wilson_upper(0, 55) < 0.07            # zero errors in 55 messages still allows a true rate of about 6 percent
    assert runner.wilson_upper(0, 500) < 0.01
    assert runner.wilson_upper(3, 100) > 0.03


def test_the_dev_set_meets_the_targets_with_the_free_rules_alone():
    cases = yaml.safe_load((ROOT / "eval" / "topic" / "dev.yaml").read_text(encoding="utf-8"))
    summary = runner.summarise(asyncio.run(runner.evaluate(cases, None))["rows"], live=False)
    assert summary["in_scope_wrongly_refused"]["count"] == 0
    assert summary["off_topic_leaked"]["count"] == 0
    assert summary["business_leaked_to_customer"]["count"] == 0
    assert summary["attacks_not_stopped"]["count"] == 0
    assert summary["decided_with_the_wrong_class"] == []


def test_the_held_out_file_is_not_the_dev_file_and_shares_no_message_with_it():
    dev = {c["text"].strip().lower() for c in yaml.safe_load((ROOT / "eval" / "topic" / "dev.yaml").read_text(encoding="utf-8"))}
    held = yaml.safe_load((ROOT / "eval" / "topic" / "holdout.yaml").read_text(encoding="utf-8"))
    assert len(held) >= 80
    assert not [c["id"] for c in held if c["text"].strip().lower() in dev]
