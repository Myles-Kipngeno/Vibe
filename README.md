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
.venv\Scripts\python.exe -m pytest      # 91 tests
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

## Accounts (optional)

By default the app runs in **local mode**: one user, one JSON file on this
machine, no sign-in. That is the most private setup and it needs no
configuration.

Turning on **accounts** gives you sign-in and sync across devices:

1. Create a project at <https://supabase.com>.
2. Apply the schema — paste `supabase/schema.sql` into the SQL editor and run it
   (or `supabase db push`). It creates the tables, enables Row Level Security on
   every one of them, and adds a trigger that gives each new signup a profile.
3. In Supabase, go to **Settings → API** and copy the project URL and the
   **anon** key.
4. Put both in `backend/.env`:

   ```
   SUPABASE_URL=https://your-project.supabase.co
   SUPABASE_ANON_KEY=eyJ...
   ```

5. Restart the backend. The app now asks you to sign in, and the frontend picks
   up the settings automatically — nothing to configure there.

To go back to local mode, blank those two values and restart.

### How the isolation actually works

This is the part worth understanding, because it is what makes it safe to put
private conversations in a shared database.

- **The backend queries Postgres as you.** Your access token is forwarded on
  every request, so PostgREST runs the query under your identity and the RLS
  policies decide what it can touch. Isolation is enforced by Postgres, not by
  filters in application code — a bug in the backend can cause an error, but it
  cannot hand you someone else's rows.
- **The service-role key is never used, and cannot be configured.** It bypasses
  RLS entirely, which would make the backend the security boundary. There is a
  test asserting the app ignores it even if the environment variable is set.
- **Clients cannot claim ownership of a row.** Every `user_id` column defaults to
  `auth.uid()`, so the owner is decided by the database from your verified token.
  The backend never sends that column.
- **The anon key being public is fine.** That is what it is for: it identifies
  the project, and grants nothing on its own. It is served from `/api/health` so
  the frontend needs no build-time config.

### What the tests do and do not prove

`tests/test_supabase_store.py` and `tests/test_auth.py` (35 tests) run against a
fake PostgREST. They prove the half this repo controls: that the user's own token
is attached, that writes never name an owner, that filters are scoped correctly,
that anonymous callers never reach the database, and that a rejection surfaces as
a clean 401.

**They cannot prove RLS itself** — that is enforced by Postgres and needs a real
project. After applying the schema, check it by hand once:

1. Sign up as two different users in two browsers.
2. Add a contact and a memory as user A.
3. Confirm user B sees an empty list.
4. In the Supabase SQL editor, run
   `select * from contact_profiles;` as each user via **Settings → API → Run as
   role: authenticated** — each should see only their own rows.

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
    storage/base.py      the storage contract both backends implement
    storage/store.py     local JSON store (default, no account needed)
    storage/supabase_store.py  Postgres via PostgREST, queried as the user
    api/                 FastAPI routes
  tests/                 91 tests, realistic conversations, fictional names
frontend/
  src/pages/             Dashboard, Workspace, Contacts, My Style, Settings
  src/components/        AlertCard (the context prompt), SuggestionCard, …
  src/lib/auth.ts        Supabase Auth only -- the browser never queries the DB
supabase/schema.sql      tables, owner-only RLS policies, signup trigger
```

**Analysis runs on your own backend and the conversation is never stored.** Only
reply *generation* sends conversation text anywhere — to the AI provider you
configured, and only when you press the button. With accounts on, what travels to
Supabase is your profile, contacts and saved memories; the conversation text
itself does not.

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

- Conversations are never stored. What is kept: your style profile, your
  contacts, and the context you explicitly chose to remember.
- With accounts on, that data is isolated per user by Row Level Security.
  Your password goes to Supabase Auth and never touches this app.
- Message bodies are kept out of the server logs (`LOG_MESSAGE_CONTENT=false`).
- Contact memories are isolated — nothing crosses from one contact to another.
- **Settings → Delete everything** wipes it all, irreversibly.
- Nothing you write is used to train any model.

---

## Where this is going

**Now (MVP).** Paste → label → analyse → alerts → suggestions → Continue/Stop/
Wait → goodnight and next-morning, with contact memory, style learning and
feedback.

**Phase 2 — personalisation.** Supabase auth and RLS are **done** (see Accounts
above). Still to come: feedback actually feeding generation, and better Sheng.

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
