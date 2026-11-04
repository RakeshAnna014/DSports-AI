"""Totals the Maven surefire module summaries in a build log (the 'Tests run: N' lines that are not per-class), so the validation report states the number executed.
Usage: python e2e/validate-count-java.py <maven-log>"""
import re
import sys

t = f = e = s = n = 0
for line in open(sys.argv[1], encoding="utf-8", errors="ignore"):
    if " -- in " in line:
        continue
    m = re.match(r"\[(?:INFO|WARNING)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$", line)
    if m:
        n += 1
        t, f, e, s = t + int(m[1]), f + int(m[2]), e + int(m[3]), s + int(m[4])
print(f"java tests: {t} run, {f} failures, {e} errors, {s} skipped (from {n} module summaries)")
sys.exit(1 if f or e else 0)
