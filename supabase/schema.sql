-- Vibe -- Postgres/Supabase schema.
--
-- Apply this once per project. The backend switches from single-user local
-- storage to accounts as soon as SUPABASE_URL and SUPABASE_ANON_KEY are set;
-- until then it keeps using the local JSON store and none of this is touched.
-- Both backends implement the same interface (backend/app/storage/base.py).
--
-- Apply with:  supabase db push   (or paste the whole file into the SQL editor)
--
-- Two things carry the security here, and both matter:
--   1. Every `user_id` defaults to `auth.uid()`, so a client physically cannot
--      create a row owned by someone else -- it never gets to send the column.
--   2. Every table has RLS enabled with an owner-only policy, so reads and
--      writes are filtered by Postgres rather than by application code.
-- The backend uses only the anon key plus the caller's own access token. The
-- service-role key bypasses RLS and is never used.

-- ---------------------------------------------------------------------------
-- Profiles: one row per authenticated user.
-- ---------------------------------------------------------------------------
create table if not exists public.profiles (
  id          uuid primary key references auth.users (id) on delete cascade,
  display_name text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- How the user texts. One row per user; every column is user-editable.
-- ---------------------------------------------------------------------------
create table if not exists public.communication_preferences (
  user_id               uuid primary key default auth.uid() references public.profiles (id) on delete cascade,
  sheng_ratio           real not null default 0.4 check (sheng_ratio between 0 and 1),
  avg_message_length    int  not null default 9  check (avg_message_length > 0),
  emoji_frequency       real not null default 0.3 check (emoji_frequency between 0 and 1),
  directness            real not null default 0.6 check (directness between 0 and 1),
  humor_style           text not null default 'playful teasing',
  flirting_style        text not null default 'light and teasing, not intense',
  -- Caps how far any suggestion may go. Explicit content is never generated.
  max_tone              text not null default 'flirtatious'
                          check (max_tone in ('friendly','playful','flirtatious','suggestive')),
  common_expressions    text[] not null default '{}',
  example_messages      text[] not null default '{}',
  learned_from_messages int  not null default 0,
  updated_at            timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- One profile per person the user talks to. Context never crosses contacts.
-- ---------------------------------------------------------------------------
create table if not exists public.contact_profiles (
  id                   uuid primary key default gen_random_uuid(),
  user_id              uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  name                 text not null,
  nickname             text,
  notes                text not null default '',
  observed_sheng_ratio real,
  observed_avg_length  int,
  -- Boundaries she stated, kept so later sessions keep respecting them.
  stated_boundaries    text[] not null default '{}',
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now()
);
create index if not exists contact_profiles_user_idx on public.contact_profiles (user_id);

-- ---------------------------------------------------------------------------
-- Remembered context, scoped to one contact. `key` matches the alert dedupe key
-- so an answered question is never asked again.
-- ---------------------------------------------------------------------------
create table if not exists public.conversation_memories (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  contact_id uuid not null references public.contact_profiles (id) on delete cascade,
  key        text not null,
  value      text not null,
  source     text not null default 'user'      check (source in ('user','conversation')),
  confidence text not null default 'confirmed' check (confidence in ('confirmed','unconfirmed')),
  created_at timestamptz not null default now(),
  -- One value per key: re-answering corrects rather than duplicates.
  unique (contact_id, key)
);
create index if not exists conversation_memories_contact_idx
  on public.conversation_memories (contact_id);

-- ---------------------------------------------------------------------------
-- Conversations. OPTIONAL and opt-in: the MVP deliberately does not persist
-- message bodies. These tables exist for users who explicitly ask to keep a
-- thread across devices in Phase 2.
-- ---------------------------------------------------------------------------
create table if not exists public.conversations (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  contact_id uuid references public.contact_profiles (id) on delete set null,
  title      text,
  goal       text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.conversation_messages (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  conversation_id uuid not null references public.conversations (id) on delete cascade,
  speaker         text not null check (speaker in ('me','them')),
  body            text not null,
  sent_at         timestamptz,          -- null when the paste had no timestamps
  position        int  not null,
  created_at      timestamptz not null default now()
);
create index if not exists conversation_messages_conv_idx
  on public.conversation_messages (conversation_id, position);

-- ---------------------------------------------------------------------------
-- Alerts the user answered, and how suggestions landed. Feedback is what makes
-- personalisation possible later; it is never shared or used to train a model
-- without explicit opt-in.
-- ---------------------------------------------------------------------------
create table if not exists public.context_alerts (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  contact_id      uuid references public.contact_profiles (id) on delete cascade,
  kind            text not null,
  priority        text not null check (priority in ('low','normal','high')),
  dedupe_key      text not null,
  question        text not null,
  answered_at     timestamptz,
  created_at      timestamptz not null default now()
);

create table if not exists public.suggestion_feedback (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  contact_id      uuid references public.contact_profiles (id) on delete set null,
  suggestion_id   text not null,
  suggestion_text text not null,
  verdict         text not null check (verdict in ('used','edited','rejected')),
  note            text not null default '',
  created_at      timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Phase 3: the curated example library. Structure only -- nothing writes here
-- yet, and uploading someone else's conversation needs their permission.
-- ---------------------------------------------------------------------------
create table if not exists public.conversation_examples (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null default auth.uid() references public.profiles (id) on delete cascade,
  situation     text,
  context       text,
  opening_line  text,
  language_mix  text,
  tone          text,
  reaction      text,
  what_worked   text,
  what_did_not  text,
  not_suitable_when text,
  media_path    text,      -- storage object path, never a public URL
  created_at    timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Row Level Security. Every table is owner-only: a user can reach their own
-- rows and nothing else. This is the whole privacy model, so it is enabled on
-- every table without exception.
-- ---------------------------------------------------------------------------
do $$
declare t text;
begin
  foreach t in array array[
    'profiles', 'communication_preferences', 'contact_profiles',
    'conversation_memories', 'conversations', 'conversation_messages',
    'context_alerts', 'suggestion_feedback', 'conversation_examples'
  ] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('drop policy if exists owner_all on public.%I', t);
  end loop;
end $$;

create policy owner_all on public.profiles
  for all using (auth.uid() = id) with check (auth.uid() = id);

create policy owner_all on public.communication_preferences
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy owner_all on public.contact_profiles
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy owner_all on public.conversation_memories
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy owner_all on public.conversations
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy owner_all on public.conversation_messages
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy owner_all on public.context_alerts
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy owner_all on public.suggestion_feedback
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy owner_all on public.conversation_examples
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- A new signup gets a profile and a default style profile automatically.
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id) values (new.id) on conflict do nothing;
  insert into public.communication_preferences (user_id) values (new.id) on conflict do nothing;
  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();
