"""Writes corpus_inventory/supplier-lead-times-and-moq.md from the SAME POC reference data the planning engine uses
(app.inventory.generator), so the policy document can never disagree with the calculations.   uv run python scripts/make_inventory_corpus.py"""
from pathlib import Path

from app.inventory import generator as G

SPORTS = ["Badminton", "Basketball", "Cricket", "Cycling", "Fitness", "Football", "Running", "Swimming", "Table Tennis", "Tennis", "Volleyball"]
OUT = Path(__file__).resolve().parent.parent / "corpus_inventory" / "supplier-lead-times-and-moq.md"

rows = []
for sport in SPORTS:
    supplier, lead = G._SUPPLIERS[G._seed("sup:" + sport) % len(G._SUPPLIERS)]
    rows.append(f"| {sport} | {supplier} | {lead} days (individual SKUs vary by about 2 days) |")

OUT.parent.mkdir(exist_ok=True)
OUT.write_text("""# Supplier Lead Times and Minimum Order Quantities (SAMPLE internal document)

This is a SAMPLE document written for the DSports AI proof of concept. The suppliers, lead times and quantities are POC reference data,
not real supplier agreements.

## Supplier by sport and typical lead time

| Sport | Supplier | Typical lead time |
|---|---|---|
""" + "\n".join(rows) + """

## Minimum order quantity (MOQ) and case packs

- Products priced below INR 5,000: the MOQ is 6, 12, 24 or 48 units depending on the SKU, and they ship in case packs of 6.
- Products priced at INR 5,000 or more (bats, bicycles, premium shoes): the MOQ is 2, 4 or 6 units and there is no case pack.
- The exact MOQ, case pack and lead time of a SKU are held in the inventory master data; the replenishment recommendation always rounds up to them.
- A purchase order quantity is raised to the MOQ even when the calculated need is smaller, and then rounded up to a whole number of case packs.

## Late deliveries

If an open purchase order will arrive after the projected stock-out date, the buyer asks the supplier to expedite it before raising a new order.
""", encoding="utf-8")
print("wrote", OUT)
