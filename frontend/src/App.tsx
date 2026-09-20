import { useCallback, useEffect, useState } from "react";

import { Banner, Button } from "./components/ui";
import { api, setUnauthorizedHandler } from "./lib/api";
import { clearShareMarker, takeSharedText } from "./lib/share";
import {
  configureAuth,
  getAccessToken,
  onAuthChange,
  signOut,
  type Session,
} from "./lib/auth";
import type { ContactProfile, Health, StyleProfile } from "./lib/types";
import Contacts from "./pages/Contacts";
import Dashboard from "./pages/Dashboard";
import Library from "./pages/Library";
import MyStyle from "./pages/MyStyle";
import Settings from "./pages/Settings";
import SignIn from "./pages/SignIn";
import Workspace from "./pages/Workspace";

const TABS = [
  { id: "dashboard", label: "Dashboard" },
  { id: "workspace", label: "Workspace" },
  { id: "contacts", label: "Contacts" },
  { id: "library", label: "Library" },
  { id: "style", label: "My Style" },
  { id: "settings", label: "Settings" },
] as const;

export type TabId = (typeof TABS)[number]["id"];

export default function App() {
  const [tab, setTab] = useState<TabId>("dashboard");
  const [health, setHealth] = useState<Health | null>(null);
  const [contacts, setContacts] = useState<ContactProfile[]>([]);
  const [style, setStyle] = useState<StyleProfile | null>(null);
  const [activeContact, setActiveContact] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);
  const [sharedText, setSharedText] = useState<string | null>(null);

  // null = not yet known. Until health tells us the mode, we render nothing
  // rather than flashing a sign-in screen at a local-mode user.
  const [authReady, setAuthReady] = useState(false);
  const [signedIn, setSignedIn] = useState(false);
  const [email, setEmail] = useState<string | null>(null);

  // A share from another app opens the Workspace with the text already in it.
  useEffect(() => {
    void (async () => {
      const text = await takeSharedText();
      clearShareMarker();
      if (text) {
        setSharedText(text);
        setTab("workspace");
      }
    })();
  }, []);

  /** Loads the user's data. Separate from health so it can run after sign-in. */
  const loadData = useCallback(async () => {
    try {
      const [c, s] = await Promise.all([api.contacts(), api.getStyle()]);
      setContacts(c);
      setStyle(s);
    } catch {
      setContacts([]);
      setStyle(null);
    }
  }, []);

  const refresh = useCallback(async () => {
    let current: Health;
    try {
      current = await api.health();
    } catch {
      setOffline(true);
      setAuthReady(true);
      return;
    }
    setOffline(false);
    setHealth(current);

    if (!current.auth_required) {
      setSignedIn(true);
      setAuthReady(true);
      await loadData();
      return;
    }

    configureAuth(current.supabase_url, current.supabase_anon_key);
    const token = await getAccessToken();
    setSignedIn(Boolean(token));
    setAuthReady(true);
    if (token) await loadData();
  }, [loadData]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // A rejected token means the session is gone; drop straight back to sign-in.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      if (health?.auth_required) setSignedIn(false);
    });
    return () => setUnauthorizedHandler(null);
  }, [health?.auth_required]);

  // Keep up with sign-out in another tab, and with token refreshes.
  useEffect(() => {
    if (!health?.auth_required) return;
    return onAuthChange((session: Session | null) => {
      setSignedIn(Boolean(session));
      setEmail(session?.user?.email ?? null);
      if (session) void loadData();
    });
  }, [health?.auth_required, loadData]);

  const openWorkspace = (contactId: string | null) => {
    setActiveContact(contactId);
    setTab("workspace");
  };

  async function handleSignOut() {
    await signOut();
    setSignedIn(false);
    setContacts([]);
    setStyle(null);
    setTab("dashboard");
  }

  if (!authReady) {
    return (
      <div className="grid min-h-full place-items-center text-sm text-muted">
        Loading…
      </div>
    );
  }

  if (health?.auth_required && !signedIn) {
    return <SignIn onSignedIn={refresh} />;
  }

  return (
    <div className="mx-auto flex min-h-full max-w-6xl flex-col px-4 pb-16 sm:px-6">
      <header className="flex flex-col gap-3 py-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <div className="grid h-9 w-9 place-items-center rounded-xl bg-accent/15 text-lg">
            💬
          </div>
          <div>
            <h1 className="text-lg font-semibold leading-tight">Vibe</h1>
            <p className="text-xs text-muted">
              {email ?? "Your conversations, thought through."}
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <nav className="flex flex-wrap gap-1 rounded-xl border border-line bg-surface/70 p-1">
            {TABS.map((t) => (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                className={
                  "rounded-lg px-3 py-1.5 text-xs font-medium transition " +
                  (tab === t.id
                    ? "bg-accent text-void"
                    : "text-muted hover:bg-raised hover:text-ink")
                }
              >
                {t.label}
              </button>
            ))}
          </nav>
          {health?.auth_required && (
            <Button size="sm" variant="ghost" onClick={handleSignOut}>
              Sign out
            </Button>
          )}
        </div>
      </header>

      {offline && (
        <div className="mb-4">
          <Banner tone="danger">
            Cannot reach the backend. Start it with{" "}
            <code className="rounded bg-void/60 px-1">uvicorn app.main:app --reload</code>{" "}
            from the <code className="rounded bg-void/60 px-1">backend</code> folder, then
            reload this page.
          </Banner>
        </div>
      )}

      {(health?.warnings ?? []).map((w) => (
        <div key={w} className="mb-4">
          <Banner tone="danger">{w}</Banner>
        </div>
      ))}

      {health?.is_mock && !offline && (
        <div className="mb-4">
          <Banner tone="warn">
            <strong>Offline mode.</strong> Analysis and alerts are real and computed on
            your machine, but reply suggestions are fixed templates, not AI output. Add an
            API key in Settings to turn on real generation.
          </Banner>
        </div>
      )}

      <main className="flex-1">
        {tab === "dashboard" && (
          <Dashboard
            contacts={contacts}
            style={style}
            health={health}
            onOpenWorkspace={openWorkspace}
            onGo={setTab}
          />
        )}
        {tab === "workspace" && (
          <Workspace
            contacts={contacts}
            contactId={activeContact}
            setContactId={setActiveContact}
            isMock={health?.is_mock ?? true}
            onContactsChanged={loadData}
            sharedText={sharedText}
            onSharedTextUsed={() => setSharedText(null)}
          />
        )}
        {tab === "contacts" && (
          <Contacts
            contacts={contacts}
            onChanged={loadData}
            onOpenWorkspace={openWorkspace}
          />
        )}
        {tab === "library" && <Library />}
        {tab === "style" && <MyStyle style={style} onChanged={loadData} />}
        {tab === "settings" && <Settings health={health} onChanged={loadData} />}
      </main>

      <footer className="mt-10 border-t border-line pt-4 text-[11px] leading-relaxed text-muted">
        {health?.auth_required
          ? "Your profile, contacts and memories are stored under your account and protected by Row Level Security. Conversation analysis runs on the server and is never saved; reply generation sends the conversation text to the AI provider configured in Settings."
          : "Everything is stored locally on this machine. Conversation analysis never leaves it; reply generation sends the conversation text to the AI provider configured in Settings."}
      </footer>
    </div>
  );
}
