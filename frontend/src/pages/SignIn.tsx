import { useState } from "react";

import { Banner, Button, Card } from "../components/ui";
import { signIn, signUp } from "../lib/auth";

/**
 * The gate shown when accounts are configured and nobody is signed in.
 *
 * Passwords go straight to Supabase Auth over HTTPS and never touch our backend
 * or our database. What comes back is a short-lived access token.
 */
export default function SignIn({ onSignedIn }: { onSignedIn: () => void }) {
  const [mode, setMode] = useState<"in" | "up">("in");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    setConfirm(false);
    try {
      if (mode === "in") {
        await signIn(email.trim(), password);
        onSignedIn();
      } else {
        const { needsConfirmation } = await signUp(email.trim(), password);
        if (needsConfirmation) setConfirm(true);
        else onSignedIn();
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "That did not work.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto flex min-h-full max-w-md flex-col justify-center px-4 py-16">
      <div className="mb-6 text-center">
        <div className="mx-auto mb-3 grid h-12 w-12 place-items-center rounded-2xl bg-accent/15 text-2xl">
          💬
        </div>
        <h1 className="text-xl font-semibold">Vibe</h1>
        <p className="mt-1 text-sm text-muted">
          {mode === "in" ? "Sign in to your conversations." : "Create an account."}
        </p>
      </div>

      <Card>
        <form onSubmit={submit} className="space-y-3">
          <label className="block">
            <span className="mb-1 block text-xs font-medium">Email</span>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
              className="w-full rounded-xl border border-line bg-void/50 px-3 py-2 text-sm"
            />
          </label>

          <label className="block">
            <span className="mb-1 block text-xs font-medium">Password</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={8}
              autoComplete={mode === "in" ? "current-password" : "new-password"}
              className="w-full rounded-xl border border-line bg-void/50 px-3 py-2 text-sm"
            />
            {mode === "up" && (
              <span className="mt-1 block text-[11px] text-muted">
                At least 8 characters.
              </span>
            )}
          </label>

          {error && <Banner tone="danger">{error}</Banner>}
          {confirm && (
            <Banner tone="accent">
              Check your email for a confirmation link, then come back and sign in.
            </Banner>
          )}

          <Button type="submit" variant="primary" disabled={busy}>
            {busy ? "…" : mode === "in" ? "Sign in" : "Create account"}
          </Button>
        </form>

        <div className="mt-4 border-t border-line pt-3 text-center">
          <button
            onClick={() => {
              setMode(mode === "in" ? "up" : "in");
              setError(null);
              setConfirm(false);
            }}
            className="text-xs text-muted transition hover:text-accent"
          >
            {mode === "in"
              ? "No account yet? Create one"
              : "Already have an account? Sign in"}
          </button>
        </div>
      </Card>

      <p className="mt-5 text-center text-[11px] leading-relaxed text-muted">
        Your conversations are stored under your account only. Row Level Security in
        the database means no other account can read them — not even by accident.
      </p>
    </div>
  );
}
