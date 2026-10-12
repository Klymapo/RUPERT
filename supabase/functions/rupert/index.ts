import { createClient } from "npm:@supabase/supabase-js@2";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });

function getBearer(req: Request): string | null {
  const raw = req.headers.get("Authorization") ?? "";
  return raw.toLowerCase().startsWith("bearer ") ? raw.slice(7).trim() : null;
}

function normalize(value: string): string {
  return value.trim().replace(/\s+/g, " ");
}

function deterministic(message: string) {
  const text = normalize(message);
  let m = text.match(/^buscar\s+(.+)$/i);
  if (m) return { action: "memory.search", risk: "read", target: m[1].trim() };
  m = text.match(/^leer\s+(.+)$/i);
  if (m) return { action: "memory.read", risk: "read", target: m[1].trim() };
  m = text.match(/^añadir\s+(.+?)\s*::\s*(.+)$/i);
  if (m) return { action: "memory.append", risk: "write", target: m[1].trim(), content: m[2].trim() };
  return null;
}

async function cloudflarePrompt(prompt: string): Promise<string> {
  const account = Deno.env.get("CF_ACCOUNT_ID");
  const token = Deno.env.get("CF_API_TOKEN");
  const model = Deno.env.get("CF_TEXT_MODEL") ?? "@cf/meta/llama-3.1-8b-instruct";
  if (!account || !token) {
    throw new Error("Cloudflare Workers AI no está configurado todavía.");
  }

  const url = `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(account)}/ai/run/${model}`;
  const res = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ prompt }),
  });
  const payload = await res.json();
  if (!res.ok || !payload?.success) {
    const detail = payload?.errors?.[0]?.message ?? `HTTP ${res.status}`;
    throw new Error(`Workers AI: ${detail}`);
  }
  const text = payload?.result?.response ?? payload?.result?.text;
  if (!text || typeof text !== "string") throw new Error("Workers AI devolvió una respuesta vacía.");
  return text.trim();
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const token = getBearer(req);
  if (!token) return json({ error: "unauthorized" }, 401);

  const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")!;
  const supabase = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { persistSession: false },
  });

  const { data: authData, error: authError } = await supabase.auth.getUser(token);
  if (authError || !authData.user) return json({ error: "unauthorized" }, 401);
  const user = authData.user;

  let body: { message?: string; source?: string; session_id?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const message = normalize(body.message ?? "");
  const source = normalize(body.source ?? "mobile") || "mobile";
  const sessionId = normalize(body.session_id ?? "default") || "default";
  if (!message) return json({ error: "message_required" }, 400);

  await supabase.from("rupert_messages").insert({
    user_id: user.id,
    session_id: sessionId,
    role: "user",
    content: message,
    metadata: { source },
  });

  const route = deterministic(message);
  if (route?.action === "memory.search") {
    const q = route.target.replace(/[%,]/g, " ");
    const { data, error } = await supabase
      .from("rupert_memory")
      .select("id,kind,title,content,updated_at")
      .or(`title.ilike.%${q}%,content.ilike.%${q}%`)
      .order("updated_at", { ascending: false })
      .limit(8);
    if (error) return json({ error: error.message }, 500);
    await supabase.from("rupert_audit").insert({ user_id: user.id, source, action_name: route.action, risk: route.risk, status: "completed", payload: { query: route.target, count: data?.length ?? 0 } });
    return json({ mode: "deterministic", action: route.action, reply: `Encontré ${data?.length ?? 0} resultado(s).`, data });
  }

  if (route?.action === "memory.read") {
    const { data, error } = await supabase
      .from("rupert_memory")
      .select("id,kind,title,content,updated_at")
      .ilike("title", route.target)
      .order("updated_at", { ascending: false })
      .limit(1)
      .maybeSingle();
    if (error) return json({ error: error.message }, 500);
    await supabase.from("rupert_audit").insert({ user_id: user.id, source, action_name: route.action, risk: route.risk, status: "completed", payload: { title: route.target, found: Boolean(data) } });
    return json({ mode: "deterministic", action: route.action, reply: data ? data.content : `No encontré “${route.target}”.`, data });
  }

  if (route?.action === "memory.append") {
    const { data: existing } = await supabase
      .from("rupert_memory")
      .select("id,content")
      .ilike("title", route.target)
      .order("updated_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    let error = null;
    if (existing?.id) {
      ({ error } = await supabase
        .from("rupert_memory")
        .update({ content: `${existing.content ?? ""}${existing.content ? "\n\n" : ""}${route.content}` })
        .eq("id", existing.id));
    } else {
      ({ error } = await supabase.from("rupert_memory").insert({
        user_id: user.id,
        kind: "note",
        title: route.target,
        content: route.content,
        metadata: { created_by: "rupert" },
      }));
    }
    if (error) return json({ error: error.message }, 500);
    await supabase.from("rupert_audit").insert({ user_id: user.id, source, action_name: route.action, risk: route.risk, status: "completed", payload: { title: route.target } });
    return json({ mode: "deterministic", action: route.action, reply: `Añadí el contenido a “${route.target}”.` });
  }

  // Unknown language is reasoning only. The model never receives an execution channel.
  const [{ data: memory }, { data: recent }] = await Promise.all([
    supabase.from("rupert_memory").select("title,content,updated_at").order("updated_at", { ascending: false }).limit(8),
    supabase.from("rupert_messages").select("role,content,created_at").eq("session_id", sessionId).order("created_at", { ascending: false }).limit(10),
  ]);

  const memoryText = (memory ?? []).map((x) => `- ${x.title}: ${String(x.content).slice(0, 900)}`).join("\n");
  const historyText = [...(recent ?? [])].reverse().map((x) => `${x.role}: ${x.content}`).join("\n");
  const prompt = `Eres Rupert, un asistente personal cloud-first. Responde en español salvo que el usuario pida otro idioma.\n\nREGLAS DE SEGURIDAD:\n- Esta llamada es SOLO de razonamiento y texto.\n- No afirmes haber ejecutado, borrado, enviado, comprado o modificado nada.\n- Si el usuario pide una acción, explica qué propondrías; la capa de permisos debe autorizarla aparte.\n\nMEMORIA RELEVANTE:\n${memoryText || "(sin memoria todavía)"}\n\nCONVERSACIÓN RECIENTE:\n${historyText}\n\nUSUARIO:\n${message}\n\nRUPERT:`;

  try {
    const reply = await cloudflarePrompt(prompt);
    await supabase.from("rupert_messages").insert({ user_id: user.id, session_id: sessionId, role: "assistant", content: reply, metadata: { provider: "cloudflare", mode: "reasoning" } });
    await supabase.from("rupert_audit").insert({ user_id: user.id, source, action_name: "brain.reason", risk: "reasoning", status: "completed", payload: {} });
    return json({ mode: "reasoning", action: "brain.reason", reply });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    await supabase.from("rupert_audit").insert({ user_id: user.id, source, action_name: "brain.reason", risk: "reasoning", status: "failed", payload: { error: message } });
    return json({ error: message, mode: "reasoning" }, 503);
  }
});
