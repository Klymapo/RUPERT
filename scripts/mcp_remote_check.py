"""Read-only manual probe of prompts.chat's MCP endpoint (stdlib only)."""
import json
import sys
import urllib.error
import urllib.request

URL = "https://prompts.chat/api/mcp"
MAX_BYTES = 256 * 1024

def main():
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}).encode()
    request = urllib.request.Request(
        URL,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read(MAX_BYTES + 1)
    except (urllib.error.URLError, TimeoutError) as exc:
        raise RuntimeError("Remote MCP endpoint is unavailable") from exc
    if len(raw) > MAX_BYTES:
        raise RuntimeError("MCP response too large")
    decoded = raw.decode("utf-8")
    items = [line[6:].strip() for line in decoded.splitlines() if line.startswith("data: ")] or [decoded]
    result = None
    for item in items:
        try:
            parsed = json.loads(item)
        except json.JSONDecodeError:
            continue
        if "error" in parsed:
            raise RuntimeError("MCP server returned a protocol error")
        if "result" in parsed:
            result = parsed["result"]
            break
    if not isinstance(result, dict) or not isinstance(result.get("tools"), list):
        raise RuntimeError("No tools/list payload found")
    tools = [t.get("name") for t in result["tools"] if isinstance(t, dict)]
    if "search_prompts" not in tools:
        raise RuntimeError("search_prompts tool missing")
    print(f"Remote MCP accessible: {len(tools)} tools; search_prompts available.")

if __name__ == "__main__":
    try:
        main()
    except RuntimeError as exc:
        print(f"MCP connection check failed: {exc}", file=sys.stderr)
        sys.exit(1)
