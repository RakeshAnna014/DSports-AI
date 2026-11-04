"""Builds a small, realistic working set for looking at the planning screens: a plan with a stock recommendation, an analysis and one plan waiting for review (names start 'E2E UI', so planning_data.py --cleanup removes them).
Reads the world written by planning_data.py:  python e2e/ai/planning_showcase.py scratch_shots/planning-world.json"""
from __future__ import annotations

import json
import sys

import stack as s

world = json.load(open(sys.argv[1], encoding="utf-8-sig"))
admin, planner = s.login(world["admin"]), s.login(world["planner"])
P = lambda m, p, t, b=None: s.ai(m, "/v1/planning" + p, t, b)
st, plan = P("POST", "/plans", planner, {"name": "E2E UI cricket plan", "source": "POC_SYNTHETIC", "scope": {"sport": "Cricket"}})
assert st == 201, plan
st, _ = P("POST", f"/plans/{plan['id']}/allocations/generate", planner)
assert st == 201
st, an = P("POST", f"/plans/{plan['id']}/analysis", planner, {"use_llm": True})
assert st == 201, an
st, plan2 = P("POST", "/plans", planner, {"name": "E2E UI north bats", "source": "POC_SYNTHETIC", "scope": {"region": "N", "category": "Bat"}})
assert st == 201
st, sub = P("POST", f"/plans/{plan2['id']}/transition", planner, {"status": "SUBMITTED", "expected_version": plan2["version"]})
assert st == 200, sub
print(json.dumps({"plan": plan["id"], "queued": plan2["id"], "findings": len(an["findings"])}))
