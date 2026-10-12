from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ROUTER = (ROOT / "supabase/functions/rupert/index.ts").read_text(encoding="utf-8")
MCP = (ROOT / "supabase/functions/rupert/mcp.ts").read_text(encoding="utf-8")

class CloudMcpBoundaryTests(unittest.TestCase):
    def test_fixed_public_endpoint_and_methods_only(self):
        self.assertIn('const MCP_ENDPOINT = "https://prompts.chat/api/mcp";', MCP)
        self.assertIn('"tools/list" | "tools/call"', MCP)
        self.assertIn('name: "search_prompts"', MCP)
        self.assertNotIn("PROMPTS_API_KEY", MCP)
        self.assertNotIn("Deno.env", MCP)
        self.assertNotIn("eval(", MCP)

    def test_explicit_read_only_commands(self):
        self.assertIn('mcp.tools", risk: "read"', ROUTER)
        self.assertIn('mcp.search", risk: "read"', ROUTER)
        self.assertIn("herramientas", ROUTER)
        self.assertIn("mcp.search", ROUTER)
        self.assertNotIn('action: "mcp.write"', ROUTER)

    def test_authentication_precedes_mcp_dispatch(self):
        self.assertLess(ROUTER.index("auth.getUser(token)"), ROUTER.index('route?.action === "mcp.tools"'))

    def test_limits(self):
        self.assertIn("MAX_BYTES", MCP)
        self.assertIn("AbortSignal.timeout(7000)", MCP)
        self.assertIn('redirect: "error"', MCP)
        self.assertIn("slice(0, 120)", MCP)
        self.assertIn("limit: 5", MCP)

    def test_brain_never_receives_tool_channel(self):
        self.assertIn("Unknown language is reasoning only", ROUTER)
        self.assertIn("SOLO de razonamiento y texto", ROUTER)
        self.assertIn("LLM-generated text NEVER calls this handler", ROUTER)

if __name__ == "__main__":
    unittest.main()
