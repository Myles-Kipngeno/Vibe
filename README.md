# Vibe

A private conversation assistant. You paste a conversation, it tells you what it
can honestly read from it, asks you about the things only you could know, and
drafts replies in your own voice — Sheng + English, the way you actually text.

It is not a pickup-line generator. The thing that makes it different is what it
*refuses* to do: it will not invent a person, an event or a shared memory, and it
will not help you talk someone out of a boundary.

---

## Running it locally

You need **two terminals**. Everything runs on your machine.

### 1. Backend (the API and all the intelligence)

```bash
cd backend
py -m venv .venv                      # once
.venv\Scripts\python.exe -m pip install -r requirements.txt   # once
.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8000
```

`--reload` restarts the server whenever you edit a Python file.
Interactive API docs: <http://127.0.0.1:8000/docs>

### 2. Frontend (the interface)

```bash
cd frontend
npm install                            # once
npm run dev
```

Open <http://localhost:5173>.

The frontend never talks to an AI provider directly — it calls the backend,
which holds the key.

### Running the tests

```bash
cd backend
.venv\Scripts\python.exe -m pytest      # 56 tests
```

```bash
cd frontend
npx tsc -b                              # type-check
npm run build                           # production build
```

---

## Turning on real AI

Out of the box the app runs in **offline mode**: analysis and alerts are real,
but the reply text comes from fixed templates. The UI says so on every screen —
mock output is never presented as AI output.

To enable real generation:

1. Get a key at <https://console.anthropic.com/settings/keys>. Anthropic keys
   start with `sk-ant-`.
2. `cp backend/.env.example backend/.env` (or edit the `backend/.env` that is
   already there).
3. Set `ANTHROPIC_API_KEY=sk-ant-...` and `AI_PROVIDER=auto`.
4. Restart the backend.

`backend/.env` is git-ignored. Never put a key in frontend code or in a commit.

Switching providers is an environment change, not a code change — add a class in
`backend/app/providers/`, register it in `registry.py`, set `AI_PROVIDER`.

---

## How it is put together

```
backend/
  app/
    core/                what makes this product different -- all deterministic
      lexicon.py         Sheng/English word lists, boundary and alert phrases
      textstats.py       language-mix, emoji, low-effort-reply measurements
      parser.py          pasted text -> labelled messages (WhatsApp, "Me:", …)
      personal_context.py  raises an alert instead of guessing  <-- the core idea
      rhythm.py          engagement, flow state, Continue/Stop/Wait
      style_profile.py   learns how *you* text, from your messages only
      prompts.py         the one place the model's rules are written
      generator.py       the gates: nothing is generated past a block
    providers/           swappable model backends (anthropic, offline mock)
    storage/store.py     local JSON store; Supabase drops in behind this
    api/                 FastAPI routes
  tests/                 56 tests, realistic conversations, fictional names
frontend/
  src/pages/             Dashboard, Workspace, Contacts, My Style, Settings
  src/components/        AlertCard (the context prompt), SuggestionCard, …
supabase/schema.sql      Phase 2 tables + Row Level Security (not in use yet)
```

**Analysis runs locally and never leaves your machine.** Only reply *generation*
sends conversation text to the configured AI provider — and only when you press
the button.

---

## What the analysis actually does

Everything below is deterministic Python, not a model guess, which is why it is
testable and why it works with no API key:

- **Personal-context detection.** Named people it has never heard of, questions
  about your relatives, references to shared events (`"remember what happened at
  Naivas?"`, `"Ulimwambia ama bado?"`), picture requests, emotional shifts, and
  boundary signals. One alert per message, never three.
- **Boundary handling.** If she says she has a boyfriend, or asks you to stop,
  generation is blocked outright. The only thing it will help you write is a
  graceful exit. It does not decide on your behalf that she "didn't mean it".
- **Rhythm.** Engagement from message lengths, questions asked back, and
  one-word replies. No folk rules about waiting twenty minutes. If the paste has
  no timestamps, it says it cannot know the gaps rather than pretending.
- **Confidence.** Every reading carries a confidence level, and "what I do not
  know" is shown next to "what I can tell".

---

## Privacy

- Conversations are never written to disk. What is stored: your style profile,
  your contacts, and the context you explicitly chose to remember.
- Message bodies are kept out of the server logs (`LOG_MESSAGE_CONTENT=false`).
- Contact memories are isolated — nothing crosses from one contact to another.
- **Settings → Delete everything** wipes it all, irreversibly.
- Nothing you write is used to train any model.

---

## Where this is going

**Now (MVP).** Paste → label → analyse → alerts → suggestions → Continue/Stop/
Wait → goodnight and next-morning, with contact memory, style learning and
feedback.

**Phase 2 — personalisation.** Supabase auth and RLS (`supabase/schema.sql`),
feedback actually feeding generation, better Sheng.

**Phase 3 — human texture.** The curated example library, and evaluation sets
built from real conversations. Screenshots do not retrain a model; they build a
reference library the prompt can draw on.

**Phase 4 — Android.** A share-to-assistant flow first, because it is the only
approach that is officially supported.

**Phase 5 — integrations.** Honestly: WhatsApp and Instagram have no API for
personal DMs. Reading them in the background needs notification-listener access,
which Google restricts heavily, and auto-sending is not supported by either
platform. A custom keyboard is possible and would need its own security review.
Anything claiming otherwise is claiming something that does not exist.
