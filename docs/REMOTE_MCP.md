# Remote MCP: cloud-first, read-only pilot

Status: **code proposed / not deployed**. This PR adds a fixed-provider MCP consumer to Rupert's authenticated Supabase Edge Function. It does not install a custom MCP server into ChatGPT and does not deploy to Supabase.

## Commands (after owner deploys the Supabase function)

- **mcp herramientas** — discover the provider's public tools. Discovery only; no execution.
- **mcp buscar code review** — call the read-only public search_prompts tool via MCP.

These commands use Supabase in the cloud. No laptop needed. Queries leave Rupert and go to **prompts.chat**, a third-party service: don't submit secrets or private data. Returned prompt text is untrusted source data and is displayed through text-only DOM APIs, never executed or passed to the reasoning model.

## Security model

- Remote URL: https://prompts.chat/api/mcp (fixed, not selected by the user).
- Methods: JSON-RPC tools/list; tools/call restricted to search_prompts with bounded query and limit.
- The existing Rupert route verifies Supabase user JWT first using auth.getUser. It never sends the JWT or private credentials to the provider.
- Seven-second timeout, no redirects, maximum 256 KiB response, maximum five search results.
- Error and success events recorded in rupert_audit; query text is omitted from audit payload.
- No arbitrary MCP tools, LLM-driven actions, private prompt access or mutations.
- The provider may change or go offline; status remains unverified until deployed and tested.

## Deployment from Android

1. Review/merge the pull request and check GitHub Actions CI.
2. Deploy the updated Supabase Edge Function named **rupert**. A GitHub merge does not automatically deploy a Supabase Edge Function.
3. Sign in to the existing Rupert PWA and enter one of the commands above.
4. Optionally use the manual GitHub Actions workflow **mcp-remote-check** to validate connectivity.

## Distinguish the products

Connected ChatGPT plugins are managed in ChatGPT. Agent Skill files committed to GitHub do not automatically appear as installed ChatGPT skills. The feature here is an optional MCP consumer for Rupert's PWA, not a new direct connector in this ChatGPT account.

## Future work

Any other MCP provider requires endpoint review, per-tool schema verification, isolated auth, cost controls, confirmation for writes, and logging. Agent Browser, OmniVoice, OmniRoute and other repositories are NOT automatically connected by this pilot.
