"""Render an OpenAPI 3 JSON document as a Markdown endpoint table (generated documentation; never edit the output by hand).

    python tools/docs/openapi_to_md.py <openapi.json> <title> <source note> > docs/api/<name>.md
"""
import json
import sys
from collections import defaultdict

METHODS = ("get", "post", "put", "patch", "delete")


def main(path: str, title: str, note: str) -> None:
    spec = json.load(open(path, encoding="utf-8"))
    groups: dict[str, list[tuple]] = defaultdict(list)
    for route, item in spec.get("paths", {}).items():
        for method in METHODS:
            op = item.get(method)
            if not op:
                continue
            tag = (op.get("tags") or ["untagged"])[0]
            secured = "bearer" if (op.get("security") or spec.get("security")) else "public/none"
            codes = ",".join(sorted(op.get("responses", {})))
            summary = (op.get("summary") or op.get("operationId") or "").replace("|", "/")
            groups[tag].append((route, method.upper(), summary, secured, codes))
    total = sum(len(v) for v in groups.values())
    print(f"# {title}\n\n{note}\n\nGenerated from the service's own OpenAPI document by `tools/docs/openapi_to_md.py` - {total} operations. "
          "Role rules are enforced in code (see the authorization matrix); the `Auth` column shows only whether the OpenAPI document declares a bearer requirement.\n")
    for tag in sorted(groups):
        print(f"## {tag}\n\n| Method | Path | Summary | Auth | Responses |\n|---|---|---|---|---|")
        for route, method, summary, secured, codes in sorted(groups[tag]):
            print(f"| {method} | `{route}` | {summary} | {secured} | {codes} |")
        print()


if __name__ == "__main__":
    main(*sys.argv[1:4])
