import { useCallback, useEffect, useState } from "react";

import { Banner } from "./components/ui";
import { api } from "./lib/api";
import type { ContactProfile, Health, StyleProfile } from "./lib/types";
import Contacts from "./pages/Contacts";
import Dashboard from "./pages/Dashboard";
import MyStyle from "./pages/MyStyle";
import Settings from "./pages/Settings";
import Workspace from "./pages/Workspace";

const TABS = [
  { id: "dashboard", label: "Dashboard" },
  { id: "workspace", label: "Workspace" },
  { id: "contacts", label: "Contacts" },
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

  const refresh = useCallback(async () => {
    try {
      const [h, c, s] = await Promise.all([
        api.health(),
        api.contacts(),
        api.getStyle(),
      ]);
      setHealth(h);
      setContacts(c);
      setStyle(s);
      setOffline(false);
    } catch {
      setOffline(true);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const openWorkspace = (contactId: string | null) => {
    setActiveContact(contactId);
    setTab("workspace");
  };

  return (
    <div className="mx-auto flex min-h-full max-w-6xl flex-col px-4 pb-16 sm:px-6">
      <header className="flex flex-col gap-3 py-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <div className="grid h-9 w-9 place-items-center rounded-xl bg-accent/15 text-lg">
            💬
          </div>
          <div>
            <h1 className="text-lg font-semibold leading-tight">Vibe</h1>
            <p className="text-xs text-muted">Your conversations, thought through.</p>
          </div>
        </div>

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
            onContactsChanged={refresh}
          />
        )}
        {tab === "contacts" && (
          <Contacts
            contacts={contacts}
            onChanged={refresh}
            onOpenWorkspace={openWorkspace}
          />
        )}
        {tab === "style" && <MyStyle style={style} onChanged={refresh} />}
        {tab === "settings" && <Settings health={health} onChanged={refresh} />}
      </main>

      <footer className="mt-10 border-t border-line pt-4 text-[11px] leading-relaxed text-muted">
        Everything is stored locally on this machine. Conversation analysis never leaves
        it; reply generation sends the conversation text to the AI provider configured in
        Settings.
      </footer>
    </div>
  );
}
