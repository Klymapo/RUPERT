# Rupert

**Rupert** is Atatoriz's cloud-first, phone-first, low-cost personal assistant hub.

The public GitHub repository stores code, tests, workflows and static web assets. Private memory, auth state, conversations and secrets live outside the repo.

## Current milestone — v0.10 Cloud Core

```text
📱 Phone / PWA on GitHub Pages
          │
          │ Supabase Auth JWT
          ▼
☁️ Supabase Edge Functions
     │              │
     │              └── Cloudflare Workers AI
     │                  ├─ reasoning
     │                  └─ Whisper ASR
     │
     ├── Postgres + RLS
     ├── private Storage
     └── audit trail
```

A laptop is **not required** for normal Rupert use. It becomes an optional worker only for future machine-specific jobs such as Blender, Godot, local GPU inference or access to files that exist only on that computer.

## Phone app / PWA

The mobile UI lives in `web/` and is deployed through GitHub Pages.

It provides:

- mobile chat;
- authenticated access;
- push-to-talk recording while the PWA is open;
- deterministic memory commands;
- cloud reasoning when configured;
- installable PWA behavior.

The app is configured from the phone with a Supabase project URL and publishable/anon key. Never paste a service-role key or Cloudflare token into the PWA.

## Private cloud memory

Supabase migration:

```text
supabase/migrations/20261005_001_rupert_cloud_core.sql
```

Private tables:

- `rupert_memory`
- `rupert_messages`
- `rupert_settings`
- `rupert_audit`

All are protected with Row Level Security. The private `rupert-private` Storage bucket is user-scoped as well.

## Deterministic commands — no LLM required

The cloud router currently recognizes:

```text
buscar <texto>
leer <título>
añadir <título> :: <contenido>
```

These execute against private Supabase memory without invoking an LLM.

Unknown natural language may go to the cloud reasoning backend, but that path is **text-only** and has no action execution channel.

## Voice

The PWA microphone records only while the app is in the foreground.

```text
microphone → authenticated Edge Function → Cloudflare Whisper → transcript → Rupert router
```

Audio is not stored by default.

A permanent background wake word is intentionally not part of the web MVP because mobile operating systems can suspend browser/PWA microphone activity. A tiny native phone companion can be added later if always-listening activation becomes important.

## Cloud backend

Supabase Edge Functions:

- `rupert` — deterministic routing + safe reasoning adapter
- `rupert-transcribe` — authenticated ASR proxy

Expected backend-only secrets for optional Cloudflare Workers AI:

```text
CF_ACCOUNT_ID
CF_API_TOKEN
CF_TEXT_MODEL=@cf/meta/llama-3.1-8b-instruct
CF_ASR_MODEL=@cf/openai/whisper-large-v3-turbo
```

These secrets must never reach GitHub Pages.

## GitHub Pages

`.github/workflows/pages.yml` deploys `web/` from `main`.

If Pages is not already enabled, use repository **Settings → Pages → Source: GitHub Actions** once. No laptop is required.

## Legacy / optional local node

The previous local components remain available and can later become an optional desktop worker:

- Obsidian bridge
- whisper.cpp
- Piper
- openWakeWord
- Ollama
- permission kernel
- skill registry

Nothing forces the phone/cloud path to depend on them.

[Auditoría de resiliencia, privacidad y CI](docs/AUDITORIA_REPOS_2026-10-12.md) — código público, memoria privada y requisitos de publicación.

## Security

The central rules remain:

- reasoning is not authorization;
- unknown text never receives an execution channel;
- private state never belongs in the public repository;
- client-side credentials are limited to publishable Supabase credentials;
- user data is protected by Auth + RLS;
- provider secrets stay server-side;
- destructive actions are absent from the v0.10 cloud MVP.

## Cost policy

Target: **as close to $0/month as practical**.

GitHub public repo + Pages/Actions handle the public code/UI/CI. Supabase handles private state/auth. Cloudflare Workers AI is optional and only used when reasoning or transcription actually needs it.

See `docs/CLOUD.md`, `docs/ARCHITECTURE.md`, `docs/SECURITY.md`, `docs/PERMISSIONS.md` and `docs/SKILLS.md`.
