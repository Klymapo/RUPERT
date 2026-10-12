import { createClient } from "https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2.105.0/+esm";

const CONFIG_KEY = "rupert_cloud_config_v1";
const SESSION_KEY = "rupert_chat_session_v1";

const $ = (id) => document.getElementById(id);
const statusText = $("statusText");
const statusDot = $("statusDot");
const authView = $("authView");
const chatView = $("chatView");
const messages = $("messages");
const promptInput = $("promptInput");
const sendBtn = $("sendBtn");
const micBtn = $("micBtn");
const settingsDialog = $("settingsDialog");

let supabase = null;
let session = null;
let mediaRecorder = null;
let recordingChunks = [];
let recordingStream = null;

function readConfig() {
  try { return JSON.parse(localStorage.getItem(CONFIG_KEY) || "null"); }
  catch { return null; }
}

function saveConfig(config) {
  localStorage.setItem(CONFIG_KEY, JSON.stringify(config));
}

function chatSessionId() {
  let value = localStorage.getItem(SESSION_KEY);
  if (!value) {
    value = crypto.randomUUID();
    localStorage.setItem(SESSION_KEY, value);
  }
  return value;
}

function setStatus(text, ok = false) {
  statusText.textContent = text;
  statusDot.classList.toggle("online", ok);
}

function addMessage(role, text, extra = null) {
  const wrap = document.createElement("article");
  wrap.className = `message ${role}`;
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  bubble.textContent = text;
  wrap.appendChild(bubble);

  if (Array.isArray(extra) && extra.length) {
    const list = document.createElement("div");
    list.className = "results";
    extra.slice(0, 8).forEach((item) => {
      const card = document.createElement("div");
      card.className = "result-card";
      const title = document.createElement("strong");
      title.textContent = item.title || item.kind || "Resultado";
      const content = document.createElement("p");
      content.textContent = String(item.content || "").slice(0, 240);
      card.append(title, content);
      list.appendChild(card);
    });
    wrap.appendChild(list);
  }

  messages.appendChild(wrap);
  messages.scrollTop = messages.scrollHeight;
}

async function configure(config) {
  if (!config?.url || !config?.key) {
    supabase = null;
    session = null;
    authView.classList.add("hidden");
    chatView.classList.add("hidden");
    setStatus("Sin configurar", false);
    return;
  }

  supabase = createClient(config.url, config.key, {
    auth: { persistSession: true, autoRefreshToken: true, detectSessionInUrl: true },
  });

  const { data } = await supabase.auth.getSession();
  session = data.session;
  supabase.auth.onAuthStateChange((_event, next) => {
    session = next;
    renderAuthState();
  });
  renderAuthState();
}

function renderAuthState() {
  if (!supabase) return;
  if (session) {
    authView.classList.add("hidden");
    chatView.classList.remove("hidden");
    $("logoutBtn").classList.remove("hidden");
    setStatus(`En línea · ${session.user.email ?? "usuario"}`, true);
    if (!messages.children.length) {
      addMessage("assistant", "Rupert cloud está listo. Los comandos conocidos se ejecutan sin LLM; el resto pasa al cerebro cloud sin permisos de acción.");
    }
  } else {
    authView.classList.remove("hidden");
    chatView.classList.add("hidden");
    $("logoutBtn").classList.add("hidden");
    setStatus("Configurado · inicia sesión", false);
  }
}

async function signIn() {
  const email = $("emailInput").value.trim();
  const msg = $("authMessage");
  if (!email) return;
  msg.textContent = "Enviando…";
  const redirect = `${location.origin}${location.pathname}`;
  const { error } = await supabase.auth.signInWithOtp({ email, options: { emailRedirectTo: redirect } });
  msg.textContent = error ? error.message : "Listo. Revisa tu correo y abre el enlace desde este dispositivo.";
}

async function sendMessage(text = promptInput.value) {
  const message = String(text || "").trim();
  if (!message || !supabase || !session) return;
  promptInput.value = "";
  addMessage("user", message);
  sendBtn.disabled = true;
  setStatus("Rupert está pensando…", true);

  try {
    const { data, error } = await supabase.functions.invoke("rupert", {
      body: { message, source: "mobile", session_id: chatSessionId() },
    });
    if (error) {
      addMessage("assistant", `Error: ${error.message}`);
    } else if (data?.error) {
      addMessage("assistant", `Error: ${data.error}`);
    } else {
      addMessage("assistant", data?.reply ?? "Sin respuesta.", data?.data ?? null);
    }
  } catch (error) {
    // Los fallos de red no deben dejar el botón Enviar bloqueado.
    addMessage("assistant", `Sin conexión con Rupert: ${error instanceof Error ? error.message : "error inesperado"}`);
  } finally {
    sendBtn.disabled = false;
    setStatus(session ? `En línea · ${session.user.email ?? "usuario"}` : "Sin sesión", Boolean(session));
  }
}

async function transcribe(blob) {
  if (!supabase || !session) return;
  setStatus("Transcribiendo audio…", true);
  const config = readConfig();
  const form = new FormData();
  form.append("audio", blob, "rupert.webm");
  form.append("language", "es");

  const res = await fetch(`${config.url}/functions/v1/rupert-transcribe`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${session.access_token}`,
      apikey: config.key,
    },
    body: form,
  });
  const payload = await res.json().catch(() => ({}));
  if (!res.ok || payload.error) {
    addMessage("assistant", `No pude transcribir: ${payload.error || `HTTP ${res.status}`}`);
    setStatus("Error de voz", false);
    return;
  }
  const text = String(payload.text || "").trim();
  if (!text) {
    addMessage("assistant", "No detecté voz suficiente.");
    return;
  }
  await sendMessage(text);
}

async function toggleMic() {
  if (mediaRecorder?.state === "recording") {
    mediaRecorder.stop();
    micBtn.classList.remove("recording");
    micBtn.textContent = "🎙️";
    return;
  }

  if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
    addMessage("assistant", "Este navegador no permite grabación directa. Puedes seguir escribiendo a Rupert.");
    return;
  }

  try {
    recordingStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    recordingChunks = [];
    mediaRecorder = new MediaRecorder(recordingStream);
    mediaRecorder.ondataavailable = (event) => {
      if (event.data?.size) recordingChunks.push(event.data);
    };
    mediaRecorder.onstop = async () => {
      recordingStream?.getTracks().forEach((track) => track.stop());
      const blob = new Blob(recordingChunks, { type: mediaRecorder.mimeType || "audio/webm" });
      mediaRecorder = null;
      await transcribe(blob);
    };
    mediaRecorder.start();
    micBtn.classList.add("recording");
    micBtn.textContent = "■";
    setStatus("Escuchando… toca ■ para terminar", true);
  } catch (error) {
    addMessage("assistant", `Micrófono no disponible: ${error.message || error}`);
  }
}

$("settingsBtn").addEventListener("click", () => {
  const config = readConfig() || {};
  $("supabaseUrl").value = config.url || "";
  $("supabaseKey").value = config.key || "";
  settingsDialog.showModal();
});

$("saveConfigBtn").addEventListener("click", async (event) => {
  event.preventDefault();
  const url = $("supabaseUrl").value.trim().replace(/\/$/, "");
  const key = $("supabaseKey").value.trim();
  if (!/^https:\/\/.+\.supabase\.(co|net)$/i.test(url) && !/^https:\/\//i.test(url)) {
    alert("Escribe una URL HTTPS válida de Supabase.");
    return;
  }
  if (!key) return;
  saveConfig({ url, key });
  settingsDialog.close();
  await configure({ url, key });
});

$("clearConfigBtn").addEventListener("click", async () => {
  if (supabase) await supabase.auth.signOut().catch(() => {});
  localStorage.removeItem(CONFIG_KEY);
  location.reload();
});

$("logoutBtn").addEventListener("click", async () => {
  await supabase?.auth.signOut();
  settingsDialog.close();
});

$("loginBtn").addEventListener("click", signIn);
sendBtn.addEventListener("click", () => sendMessage());
micBtn.addEventListener("click", toggleMic);
promptInput.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    sendMessage();
  }
});

document.querySelectorAll("[data-prompt]").forEach((button) => {
  button.addEventListener("click", () => sendMessage(button.dataset.prompt));
});

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("./sw.js").catch(() => {});
}

const config = readConfig();
if (!config) {
  setStatus("Configura Supabase para empezar", false);
  settingsDialog.showModal();
} else {
  configure(config).catch((error) => {
    setStatus(`Configuración inválida: ${error.message || error}`, false);
  });
}
