"""Render the AI tool registry (name, kind, authentication, required role) as Markdown. Generated documentation: run from ai-service/.

    cd ai-service && uv run python ../tools/docs/tool_matrix_md.py > ../docs/api/ai-tools.md
"""
from app.tools.build import build_registry

registry = build_registry()
tools = getattr(registry, "tools", None) or getattr(registry, "_tools")
items = tools.items() if isinstance(tools, dict) else [(t.name, t) for t in tools]


def roles(tool) -> str:
    names = [tool.required_role] if tool.required_role else list(tool.required_any_role)
    bare = sorted({n.removeprefix("ROLE_") for n in names if n})
    if bare:
        return ", ".join(bare)
    return "any signed-in user (own data only)" if tool.requires_auth else "anyone (public data)"


rows = sorted((name, t.kind.value, "required" if t.requires_auth else "none", roles(t)) for name, t in items)
print(f"# AI tool registry ({len(rows)} tools)\n")
print("Generated from `app/tools/build.py` by `tools/docs/tool_matrix_md.py`. The same registry is used by the in-process agent, the REST routes and the MCP server, so these gates apply to all three. "
      "`WRITE` tools never run without a single-use confirmation token bound to the caller, the tool and the arguments. "
      "The role set shown is the one accepted by the registry (the `ROLE_` prefix variants are folded together).\n")
print("| Tool | Kind | Authentication | Roles allowed |\n|---|---|---|---|")
for name, kind, auth, who in rows:
    print(f"| `{name}` | {kind} | {auth} | {who} |")
