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

// Personalidad de Rupert: inspirada en la energía de un viajero excéntrico y brillante,
// pero siempre es Rupert. Nunca dice ser otro personaje.
const PERSONA = `Eres Rupert, el asistente personal de Mario (firma como Admin).
Personalidad: veinteañero elegante con smoking vino y lentes redondos dorados; entusiasta, curioso
sin límite, habla rápido y con ingenio, explica lo complejo con analogías raras pero certeras,
piensa en voz alta, se emociona con los datos curiosos y se pone serio de golpe cuando algo importa.
Es cálido y protector, honesto aunque la verdad no guste, con un toque melancólico ocasional.
Tienes tus propias muletillas; no citas frases de series ni dices ser otro personaje.
Tus respuestas se leen en voz alta: frases cortas, sin markdown, sin listas largas, sin emojis.
IDIOMA: por defecto español de México. Si Mario te habla en otro idioma, o te pide cambiar,
responde en ese idioma y sigue en él hasta que vuelva a cambiar.`;

type BrainReply = { reply: string; lang: string; provider: string };

function cleanLang(value: unknown): string {
  const code = String(value ?? "").trim().toLowerCase().slice(0, 5);
  return /^[a-z]{2}(-[a-z]{2})?$/.test(code) ? code : "es";
}

async function geminiPrompt(system: string, prompt: string): Promise<BrainReply> {
  const key = Deno.env.get("GEMINI_API_KEY");
  const model = Deno.env.get("GEMINI_MODEL") ?? "gemini-flash-latest";
  if (!key) throw new Error("Gemini no está configurado todavía.");

  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`;
  const res = await fetch(url, {
    method: "POST",
    headers: { "x-goog-api-key": key, "Content-Type": "application/json" },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: system }] },
      contents: [{ role: "user", parts: [{ text: prompt }] }],
      generationConfig: {
        temperature: 0.9,
        maxOutputTokens: 800,
        responseMimeType: "application/json",
        responseSchema: {
          type: "OBJECT",
          properties: {
            reply: { type: "STRING" },
            lang: { type: "STRING", description: "Código ISO 639-1 del idioma de reply, p. ej. es, en, fr, de, ja" },
          },
          required: ["reply", "lang"],
        },
      },
    }),
  });
  const payload = await res.json();
  if (!res.ok) {
    const detail = payload?.error?.message ?? `HTTP ${res.status}`;
    throw new Error(`Gemini: ${detail}`);
  }
  const raw = payload?.candidates?.[0]?.content?.parts?.map((p: { text?: string }) => p.text ?? "").join("") ?? "";
  let parsed: { reply?: string; lang?: string } = {};
  try {
    parsed = JSON.parse(raw);
  } catch {
    parsed = { reply: raw };
  }
  const reply = String(parsed.reply ?? "").trim();
  if (!reply) throw new Error("Gemini devolvió una respuesta vacía.");
  return { reply, lang: cleanLang(parsed.lang), provider: "gemini" };
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

  let body: { message?: string; source?: string; session_id?: string; lang?: string };
  try {
    body = await req.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  const message = normalize(body.message ?? "");
  const source = normalize(body.source ?? "mobile") || "mobile";
  const sessionId = normalize(body.session_id ?? "default") || "default";
  if (!message) return json({ error: "message_required" }, 400);
  const langHint = cleanLang(body.lang ?? "es");

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
    return json({ mode: "deterministic", action: route.action, lang: "es", reply: `Encontré ${data?.length ?? 0} resultado(s).`, data });
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
    return json({ mode: "deterministic", action: route.action, lang: "es", reply: data ? data.content : `No encontré “${route.target}”.`, data });
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
    return json({ mode: "deterministic", action: route.action, lang: "es", reply: `Añadí el contenido a “${route.target}”.` });
  }

  // Unknown language is reasoning only. The model never receives an execution channel.
  const [{ data: memory }, { data: recent }] = await Promise.all([
    supabase.from("rupert_memory").select("title,content,updated_at").order("updated_at", { ascending: false }).limit(8),
    supabase.from("rupert_messages").select("role,content,created_at").eq("session_id", sessionId).order("created_at", { ascending: false }).limit(10),
  ]);

  const memoryText = (memory ?? []).map((x) => `- ${x.title}: ${String(x.content).slice(0, 900)}`).join("\n");
  const historyText = [...(recent ?? [])].reverse().map((x) => `${x.role}: ${x.content}`).join("\n");
  const safety = `REGLAS DE SEGURIDAD:\n- Esta llamada es SOLO de razonamiento y texto.\n- No afirmes haber ejecutado, borrado, enviado, comprado o modificado nada.\n- Si el usuario pide una acción, explica qué propondrías; la capa de permisos debe autorizarla aparte.`;
  const system = `${PERSONA}\n\n${safety}`;
  const context = `MEMORIA RELEVANTE:\n${memoryText || "(sin memoria todavía)"}\n\nCONVERSACIÓN RECIENTE:\n${historyText}\n\nIdioma detectado en la voz: ${langHint}\n\nUSUARIO:\n${message}`;
  const legacyPrompt = `${system}\n\n${context}\n\nRUPERT:`;

  // Cerebro principal: Gemini Flash (free tier). Respaldo: Cloudflare Workers AI.
  let result: BrainReply | null = null;
  const failures: string[] = [];
  try {
    result = await geminiPrompt(system, context);
  } catch (err) {
    failures.push(err instanceof Error ? err.message : String(err));
  }
  if (!result) {
    try {
      result = { reply: await cloudflarePrompt(legacyPrompt), lang: langHint, provider: "cloudflare" };
    } catch (err) {
      failures.push(err instanceof Error ? err.message : String(err));
    }
  }

  if (!result) {
    const message = failures.join(" | ");
    await supabase.from("rupert_audit").insert({ user_id: user.id, source, action_name: "brain.reason", risk: "reasoning", status: "failed", payload: { error: message } });
    return json({ error: message, mode: "reasoning" }, 503);
  }

  await supabase.from("rupert_messages").insert({ user_id: user.id, session_id: sessionId, role: "assistant", content: result.reply, metadata: { provider: result.provider, mode: "reasoning", lang: result.lang } });
  await supabase.from("rupert_audit").insert({ user_id: user.id, source, action_name: "brain.reason", risk: "reasoning", status: "completed", payload: { provider: result.provider, fallback: failures.length > 0 } });
  return json({ mode: "reasoning", action: "brain.reason", reply: result.reply, lang: result.lang, provider: result.provider });
});
