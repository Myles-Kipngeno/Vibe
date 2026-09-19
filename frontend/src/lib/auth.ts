/**
 * Supabase Auth, and only Auth.
 *
 * The browser talks to Supabase to sign in and to keep a session fresh. It never
 * queries the database directly -- every data request goes to our backend, which
 * forwards the same access token to Postgres so Row Level Security applies.
 *
 * Only the *anon* key is used here, which is what it is designed for: it grants
 * nothing on its own, because RLS decides what a token can reach.
 *
 * Both the project URL and the anon key come from the backend's health response
 * rather than from build-time env vars, so one `backend/.env` configures the
 * whole app and the same built bundle works against any project. Build-time
 * `VITE_` values are still honoured as a fallback.
 */

import { createClient, type Session, type SupabaseClient } from "@supabase/supabase-js";

let client: SupabaseClient | null = null;
let configuredUrl: string | null = null;

/** Configure from the backend's health response, falling back to build-time env. */
export function configureAuth(
  url: string | null | undefined,
  anon: string | null | undefined,
): SupabaseClient | null {
  const projectUrl = url || import.meta.env.VITE_SUPABASE_URL || null;
  const anonKey = anon || import.meta.env.VITE_SUPABASE_ANON_KEY || null;

  if (!projectUrl || !anonKey) return null;
  if (client && configuredUrl === projectUrl) return client;

  client = createClient(projectUrl, anonKey, {
    auth: {
      persistSession: true,
      autoRefreshToken: true,
      detectSessionInUrl: true,
    },
  });
  configuredUrl = projectUrl;
  return client;
}

export function getAuthClient(): SupabaseClient | null {
  return client;
}

/** The current access token, refreshed if it is close to expiring. */
export async function getAccessToken(): Promise<string | null> {
  if (!client) return null;
  const { data } = await client.auth.getSession();
  return data.session?.access_token ?? null;
}

export async function signIn(email: string, password: string) {
  if (!client) throw new Error("Accounts are not configured.");
  const { error } = await client.auth.signInWithPassword({ email, password });
  if (error) throw new Error(error.message);
}

export async function signUp(email: string, password: string) {
  if (!client) throw new Error("Accounts are not configured.");
  const { data, error } = await client.auth.signUp({ email, password });
  if (error) throw new Error(error.message);
  // With email confirmation on, there is no session until the link is clicked.
  return { needsConfirmation: !data.session };
}

export async function signOut() {
  await client?.auth.signOut();
}

export function onAuthChange(handler: (session: Session | null) => void) {
  if (!client) return () => {};
  const { data } = client.auth.onAuthStateChange((_event, session) => handler(session));
  return () => data.subscription.unsubscribe();
}

export type { Session };
