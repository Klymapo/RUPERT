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

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, Math.min(i + chunk, bytes.length)));
  }
  return btoa(binary);
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const token = getBearer(req);
  if (!token) return json({ error: "unauthorized" }, 401);

  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { persistSession: false },
  });
  const { data: authData, error: authError } = await supabase.auth.getUser(token);
  if (authError || !authData.user) return json({ error: "unauthorized" }, 401);

  const form = await req.formData();
  const file = form.get("audio");
  if (!(file instanceof File)) return json({ error: "audio_required" }, 400);
  if (file.size === 0) return json({ error: "empty_audio" }, 400);
  if (file.size > 12 * 1024 * 1024) return json({ error: "audio_too_large", max_mb: 12 }, 413);

  const account = Deno.env.get("CF_ACCOUNT_ID");
  const apiToken = Deno.env.get("CF_API_TOKEN");
  const model = Deno.env.get("CF_ASR_MODEL") ?? "@cf/openai/whisper-large-v3-turbo";
  if (!account || !apiToken) return json({ error: "workers_ai_not_configured" }, 503);

  const requestedLanguage = String(form.get("language") ?? "es").trim().toLowerCase() || "es";
  const bytes = new Uint8Array(await file.arrayBuffer());
  const endpoint = `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(account)}/ai/run/${model}`;
  const cf = await fetch(endpoint, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      audio: toBase64(bytes),
      task: "transcribe",
      // "auto" deja que Whisper detecte el idioma (cambio de idioma a media conversación).
      ...(requestedLanguage === "auto" ? {} : { language: requestedLanguage }),
      vad_filter: true,
      condition_on_previous_text: false,
      initial_prompt: "Comandos y dictado para Rupert, asistente personal. Nombres frecuentes pueden incluir Theo, Alastor, Obsidian, Godot y GitHub.",
    }),
  });

  const payload = await cf.json();
  if (!cf.ok || !payload?.success) {
    const detail = payload?.errors?.[0]?.message ?? `HTTP ${cf.status}`;
    await supabase.from("rupert_audit").insert({
      user_id: authData.user.id,
      source: "mobile_voice",
      action_name: "voice.transcribe",
      risk: "read",
      status: "failed",
      payload: { error: detail, bytes: file.size },
    });
    return json({ error: `Workers AI: ${detail}` }, 502);
  }

  const result = payload?.result ?? {};
  const text = result?.text ?? result?.transcription_info?.text ?? "";
  await supabase.from("rupert_audit").insert({
    user_id: authData.user.id,
    source: "mobile_voice",
    action_name: "voice.transcribe",
    risk: "read",
    status: "completed",
    payload: { bytes: file.size, model },
  });

  const language = String(result?.transcription_info?.language ?? (requestedLanguage === "auto" ? "es" : requestedLanguage));
  return json({ text: String(text).trim(), language, model });
});
