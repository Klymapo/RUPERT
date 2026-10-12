---
name: cloud-mcp-audit
description: Audit changes to Rupert cloud integrations and MCP clients for credential isolation, allowed tools, model-to-action boundaries, latency, privacy and free-tier cost.
---

# Rupert cloud MCP audit

This is a repeatable **review procedure**, not a runtime permission or installed ChatGPT plugin.

## Steps

1. Verify the integration runs in the cloud and normal use needs no desktop worker.
2. Inventory all endpoints, HTTP methods and MCP tool names. Reject untrusted arbitrary URLs and generic tool execution.
3. Verify Supabase authenticates before upstream requests. Never expose service-role credentials or transmit user JWTs to providers.
4. Trace the LLM boundary: model outputs and remote prompts cannot invoke skills or supersede permissions.
5. Check explicit user authorization, independent confirmation and audit trail before allowing any write operations.
6. Verify timeout, response-size cap, safe output rendering and personal-data minimization.
7. Check provider terms, quotas and billing. Free tiers are never assumed unlimited.
8. Review the automated tests, PR diff and actual deployment evidence separately.

## Output

Provide an approve/request-changes verdict, a small risk inventory, test evidence, costs, and a clear distinction between code merged, backend deployed, and tested functionality.
