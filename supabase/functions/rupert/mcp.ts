/**
 * Read-only public MCP client for prompts.chat.
 *
 * Deliberately NOT a generic MCP proxy. No user-controlled URL, tool name,
 * credentials, write methods, or LLM-selected actions are accepted.
 */
const MCP_ENDPOINT = "https://prompts.chat/api/mcp";
const MAX_BYTES = 256 * 1024;

type McpEnvelope = {
  result?: {
    tools?: Array<{ name?: unknown; description?: unknown }>;
    structuredContent?: unknown;
    content?: Array<{ type?: string; text?: string }>;
  };
  error?: { message?: string };
};

function clip(value: unknown, maximum = 220): string {
  return typeof value === "string" ? value.replace(/\s+/g, " ").slice(0, maximum) : "";
}

async function readLimited(response: Response): Promise<string> {
  if (!response.body) throw new Error("empty_response");
  const reader = response.body.getReader();
  const buffers: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      size += part.value.byteLength;
      if (size > MAX_BYTES) throw new Error("response_too_large");
      buffers.push(part.value);
    }
  } finally {
    reader.releaseLock();
  }
  const merged = new Uint8Array(size);
  let at = 0;
  for (const part of buffers) {
    merged.set(part, at);
    at += part.byteLength;
  }
  return new TextDecoder().decode(merged);
}

function parseEnvelope(raw: string): McpEnvelope {
  const lines = raw.split(/\r?\n/);
  const events = lines.filter((line) => line.startsWith("data: ")).map((line) => line.slice(6).trim());
  const candidates = events.length ? events : [raw.trim()];
  for (const candidate of candidates) {
    if (!candidate || candidate === "[DONE]") continue;
    try {
      const parsed = JSON.parse(candidate);
      if (parsed && (parsed.result || parsed.error)) return parsed;
    } catch { /* skip non-JSON event */ }
  }
  throw new Error("invalid_mcp_response");
}

async function callPublicMcp(method: "tools/list" | "tools/call", params?: { name: "search_prompts"; arguments: { query: string; limit: number } }): Promise<McpEnvelope> {
  const response = await fetch(MCP_ENDPOINT, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Accept": "application/json, text/event-stream",
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, ...(params ? { params } : {}) }),
    signal: AbortSignal.timeout(7000),
    redirect: "error",
  });
  if (!response.ok) throw new Error("mcp_upstream_unavailable");
  const envelope = parseEnvelope(await readLimited(response));
  if (envelope.error) throw new Error("mcp_upstream_error");
  return envelope;
}

function unpackToolContent(envelope: McpEnvelope): unknown {
  const structured = envelope.result?.structuredContent;
  if (structured) return structured;
  const raw = envelope.result?.content?.find((block) => block.type === "text")?.text;
  if (!raw) return null;
  try { return JSON.parse(raw); }
  catch { return null; }
}

export async function listPublicMcpTools(): Promise<{ reply: string; data: Array<{ title: string; content: string }> }> {
  const envelope = await callPublicMcp("tools/list");
  const tools = Array.isArray(envelope.result?.tools) ? envelope.result!.tools! : [];
  const data = tools.slice(0, 12).map((item) => ({
    title: clip(item.name, 70),
    content: clip(item.description, 240),
  })).filter((item) => Boolean(item.title));
  return {
    reply: "Herramientas publicadas por prompts.chat (solo consulta; Rupert NO puede ejecutarlas automáticamente): " +
      (data.map((x) => x.title).join(", ") || "No se encontraron herramientas."),
    data,
  };
}

export async function searchPublicMcpPrompts(query: string): Promise<{ reply: string; data: Array<{ title: string; content: string }> }> {
  const clean = query.trim().replace(/\s+/g, " ").slice(0, 120);
  if (clean.length < 2) throw new Error("query_too_short");
  const envelope = await callPublicMcp("tools/call", {
    name: "search_prompts",
    arguments: { query: clean, limit: 5 },
  });
  const content = unpackToolContent(envelope);
  const record = content && typeof content === "object" ? content as Record<string, unknown> : {};
  const prompts = Array.isArray(record.prompts) ? record.prompts : [];
  const data = prompts.slice(0, 5).map((item: Record<string, unknown>) => ({
    title: clip(item.title, 120),
    content: clip(item.description || item.content, 350),
  })).filter((item: { title: string }) => Boolean(item.title));
  return {
    reply: data.length
      ? "Encontré " + data.length + " prompt(s) públicos. Son referencias externas; no los ejecutaré como instrucciones."
      : "No hubo resultados que pudiera interpretar para esta búsqueda.",
    data,
  };
}
