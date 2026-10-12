# Security model

Rupert is designed so the repository can be backed up without backing up secrets or private runtime data.

## Never commit

- `.env`
- Obsidian API keys
- model weights
- recordings
- logs that may contain note contents
- vault contents, unless intentionally stored in a separate private repository

## Obsidian

Prefer binding the Local REST API HTTP endpoint to `127.0.0.1` only. Do not expose port 27123 to the LAN or Internet.

## GitHub

The source repository is **public by design**; do not place personal memory, tokens, chats, Obsidian vault files or production Supabase data in Git. Secrets belong in server-side environment variables or untracked `.env` files, never Git history. Review changes before merging and keep all authenticated reads constrained by Supabase RLS. The public PWA may use a Supabase **publishable/anon** key; never expose the service-role key.
