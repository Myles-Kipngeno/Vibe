# Vibe

[![checks](https://github.com/Myles-Kipngeno/Vibe/actions/workflows/ci.yml/badge.svg)](https://github.com/Myles-Kipngeno/Vibe/actions/workflows/ci.yml)

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
.venv\Scripts\python.exe -m pytest      # 232 tests
```

```bash
cd frontend
npm test                                # 22 component tests
npx tsc -b                              # type-check
npm run build                           # production build
```

All five checks — the backend tests, the offline eval set, the sharing
verification, the frontend tests and the frontend build — run on every push to
`main`, which is what the badge at the
top reports. `--generate` is deliberately not among them: it costs money and is
not deterministic, so it stays a thing you run on purpose.

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
   SUPABASE_ANON_KEY=sb_publishable_...   # or a legacy eyJ... anon key
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
project. `scripts/verify_rls.py` does that part. Apply the schema, then:

```bash
python scripts/verify_rls.py --url https://<ref>.supabase.co --anon-key eyJ...
```

It signs up two throwaway accounts and attacks the boundary from both sides —
straight at PostgREST, where RLS lives, and through the running backend. It
checks that Bob cannot list, read by id, update or delete Alice's rows, cannot
plant a row owned by her, and that the anon key alone reads nothing. Then it
deletes the rows it made.

It needs no service-role key on purpose: if it could reach another user's data
with only an anon key and an ordinary login, so could anyone.

One setup note — new projects have **Confirm email** on, so signup returns no
session and the script cannot get tokens. Turn it off for the run
(**Authentication → Sign In / Providers → Email**), then turn it back on. The
two test users stay in **Authentication → Users**; delete them there.

---

## How it is put together

```
backend/
  Dockerfile             the backend as a long-running process, for deploying
  app/
    core/                what makes this product different -- all deterministic
      lexicon.py         Sheng/English word lists, verb morphology, alert phrases
      textstats.py       language-mix, emoji, low-effort-reply measurements
      parser.py          pasted text -> labelled messages (WhatsApp, "Me:", …)
      personal_context.py  raises an alert instead of guessing  <-- the core idea
      rhythm.py          engagement, flow state, Continue/Stop/Wait
      style_profile.py   learns how *you* text, from your messages only
      prompts.py         the one place the model's rules are written
      feedback_signal.py what his used/edited/rejected verdicts imply
      example_library.py picks curated examples, and refuses to more often
      evaluation.py      turns the README's promises into checkable properties
      generator.py       the gates: nothing is generated past a block
    providers/           swappable model backends (anthropic, offline mock)
    storage/base.py      the storage contract both backends implement
    storage/store.py     local JSON store (default, no account needed)
    storage/supabase_store.py  Postgres via PostgREST, queried as the user
    api/                 FastAPI routes
  tests/                 232 tests, realistic conversations, fictional names
frontend/
  public/                PWA manifest, the share-target service worker, icons
  src/pages/             Dashboard, Workspace, Contacts, Library, My Style, Settings
  src/components/        AlertCard (the context prompt), SuggestionCard, …
  *.test.tsx             what the card records, and what it must not
  src/lib/auth.ts        Supabase Auth only -- the browser never queries the DB
supabase/schema.sql      tables, owner-only RLS policies, signup trigger
render.yaml              the backend as a Render service, secrets prompted for
evals/cases.json         fixed conversations and what each one must do
scripts/run_evals.py     runs them; offline by default, --generate for real
scripts/verify_sharing.mjs  drives both share directions without a browser
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
- **A library that refuses more than it offers.** Examples you save are matched
  to the situation you are actually in, never shown past a boundary, and
  dropped when you marked them wrong for this kind of moment. Two at most,
  and the model is told plainly that they are there for register — a line
  that worked on someone else is not a line, it is a coincidence.
- **Learning from your verdicts.** Every suggestion you send, edit or throw out
  is compared against the others: length, Sheng level, emojis, whether it ends
  in a question. A difference only reaches the prompt when it is big enough to
  survive a handful of samples, and anything you typed in the note field is
  quoted to the model verbatim, because your words beat our summary of them.
  Rejecting a suggestion asks why, in one optional line. Judging the same
  suggestion again replaces your earlier verdict rather than counting twice.
  Under four verdicts it says nothing at all — four rejections are as likely to
  be four weak suggestions as a standing preference.
- **Confidence.** Every reading carries a confidence level, and "what I do not
  know" is shown next to "what I can tell".

---

## On your phone

Vibe installs as an app on Android and appears in the share sheet: long-press a
conversation in WhatsApp, **Share → Vibe**, and it opens with the text already
in the paste box. Nothing is analysed until you press the button — the labels
may be wrong, and reading a conversation you have not asked about is the one
thing this app should never do by itself.

Going the other way, a reply you pick has a **Send** button that hands it to the
share sheet: choose WhatsApp, pick the chat, and it arrives in the box ready to
send. It is still you sending it — nothing can put a message in a chat on your
behalf, on any platform — but it is one tap rather than a copy, a task switch
and a paste. Where there is no share sheet the button says **Copy** and does
what it always did. Backing out of the share sheet records nothing: changing
your mind is not a reply you sent, and logging it as one would quietly poison
what the app learns from your verdicts.

The incoming share is a **POST**, which is why there is a service worker. A GET share
target would need no worker at all, but the conversation would travel as a URL
query string and land in browser history and in any log on the way. Instead the
worker parks it in a cache entry the page reads exactly once and deletes.

`node scripts/verify_sharing.mjs` exercises that handoff outside a browser
— the worker is plain JavaScript, so it runs against stubs. It proves the share
is received, the conversation reaches the page unchanged, and it never appears
in a URL. It cannot prove Chrome offers "Add to home screen" or lists Vibe in
the share sheet; that needs a device.

### Two things it needs first

**HTTPS.** Service workers and share targets require a secure context.
`localhost` counts; a LAN address like `http://192.168.1.5:5173` does not, so
the worker will not register and Vibe will not appear in the share sheet. You
need a real certificate or a tunnel. A tunnel puts your backend on the public
internet, which for this app deserves a moment's thought.

**Accounts, before you expose anything.** In local mode there is no sign-in,
because there is nothing to sign in to — and that is fine while the backend
only listens on `127.0.0.1`. The moment it listens anywhere else, every
endpoint is open to whoever can reach it: your contacts, everything you have
had it remember, and `DELETE /api/data`, which wipes the lot. Turn on Supabase
accounts (see **Accounts** above) before the backend leaves your machine.

## Putting it online

The two halves go to different places, and the order matters.

**The backend needs a host that keeps a process running** — Render, Fly,
Railway, a VPS. `render.yaml` is a blueprint for Render: create a Blueprint
from this repository and the service arrives configured, prompting for the
values that must not live in a public file. `backend/Dockerfile` is there for
hosts that want an image instead.

It is deliberately not a serverless function: the local JSON store writes to
disk, and a serverless filesystem is per-invocation, so local mode would appear
to work and lose every write.

**Turn accounts on first.** This is not optional advice. In local mode there is
no sign-in, because nothing outside this machine can ask the backend anything.
The moment it is reachable from elsewhere, every endpoint is open to whoever
finds the URL — your contacts, everything you have had it remember, and
`DELETE /api/data`, which wipes all of it. The app enforces this: name a
non-localhost `CORS_ORIGINS` without `SUPABASE_URL` and `SUPABASE_ANON_KEY`
set, and it refuses to start rather than serve. It refuses on a hosting
platform too, whatever CORS says — CORS is enforced by browsers, so a public
URL with the default localhost origins is still open to anything that is not
one.

**The frontend goes to Vercel** (or any static host). Select the `frontend`
directory on Vercel; `frontend/vercel.json` handles SPA routing and service worker
headers. Two environment variables:

```
VITE_API_URL=https://<your-backend-host>
```

`VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` are optional — the frontend
reads them from `/api/health` once it can reach the backend. Without
`VITE_API_URL` the built app requests `/api` from its own domain and reaches
nothing, which is the first thing to check if a deployment loads but does not
work.

Then point the backend back at it:

```
CORS_ORIGINS=https://<your-frontend-domain>
```

Serving the frontend over HTTPS is also what the Android share target needs
(see *On your phone*), so a deployment gets that for free.

## Checking it still behaves

Unit tests prove a function does what it says. The eval set proves the product
still keeps its promises across whole conversations:

```bash
python scripts/run_evals.py              # offline, no key, must always pass
python scripts/run_evals.py --generate   # also checks real model output
```

`evals/cases.json` holds fixed, fictional conversations and what each one must
produce — a boundary must block, an unknown name must be asked about, and an
ordinary chat must still get a reply, which matters just as much: a product
that refuses everything is useless. Each case says why it exists.

The offline half needs no API key and exits non-zero on a regression, so it can
gate a commit. `--generate` additionally asks the configured provider for real
replies and checks them against the claims on this page: no invented people,
Sheng at roughly your own level and not far either side of it, no stacked
emojis, no reciting the example library, and options that differ by more than
wording. That half costs money
and is not deterministic, so a failure there is a reason to go and look rather
than proof of a bug. Without a real provider configured it skips those checks
rather than running them against templates.

## What it refuses to spend

`/suggest` is the only endpoint that calls a paid model, and the key it spends
belongs to whoever deployed this rather than to whoever is asking. Two ceilings
keep that from being anyone's problem.

**How often.** Six generations a minute and sixty an hour, per signed-in
account (per address in local mode). `SUGGEST_PER_MINUTE` and
`SUGGEST_PER_HOUR` change them; 0 turns one off. A refusal is a 429 carrying
`Retry-After`, so a client can wait rather than hammer. The counter lives in
the process, which is enough for one instance and wrong for several — scale
past one and it needs a shared counter.

**How much.** Every request field reaches the prompt and is billed per token,
so each is bounded: 4,000 characters a message, 500 messages, 100,000
characters a paste, and caps on the context answers and the already-shown list.
None should ever be met by someone using the app.

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

**Phase 2 — personalisation.** Done: Supabase auth and RLS (see Accounts
above); feedback feeding generation, so what you send, edit and throw out
shapes the next suggestions; and Sheng measured by verb shape rather than by a
word list, with a countable Sheng budget in the prompt instead of an adjective.
The added vocabulary in `lexicon.py` is general Nairobi Sheng, not your own —
it is marked as such, and striking what rings false is the intended way to use
it.

**Phase 3 — done.** The example library is in: examples are stored,
matched to the situation and shown to the model as reference. It is a reference
and not a script — nothing is offered past a boundary, an example carries the
conditions he marked it wrong for, at most two are ever shown, and the prompt
says outright that the lines are not to be reused. The Library tab is where you
add them, and the form has nowhere to put her messages on purpose. The eval set
is in too (see below).

**Phase 4 — Android.** The share-to-assistant flow is built (see *On your
phone* above). Running it on a real device needs HTTPS and, honestly, accounts
turned on first.

**Phase 5 — integrations.** Done, in the only form that exists. The share sheet
carries a conversation in and a reply back out; see *On your phone*. Everything
else people mean by "integration" here is not a feature that has not been built
yet, it is a thing the platforms do not offer:

- **No API for personal DMs.** WhatsApp's Business API covers business accounts
  messaging customers, not your own chats. Instagram's messaging API is for
  professional accounts. Neither reaches a personal conversation.
- **Auto-sending is not supported by either platform**, so nothing can put a
  message in a chat except you. That is why the reply goes to the share sheet.
- **Reading chats in the background** needs Android's notification-listener
  access, which Google restricts to apps whose core function requires it, and
  which would mean this app watching every notification on the phone. That is a
  worse trade than a share sheet, not a better one.
- **A custom keyboard is technically possible** and stays possible. It is also
  an input method with sight of everything typed on the device, including
  passwords, in every app. It would need its own security review and a much
  stronger reason than saving a tap.

Anything claiming otherwise is claiming something that does not exist.
