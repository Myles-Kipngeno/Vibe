import { useState } from "react";

import { Button, Card, Empty, Pill } from "../components/ui";
import { api } from "../lib/api";
import type { ContactProfile } from "../lib/types";

export default function Contacts({
  contacts,
  onChanged,
  onOpenWorkspace,
}: {
  contacts: ContactProfile[];
  onChanged: () => void;
  onOpenWorkspace: (id: string) => void;
}) {
  const [name, setName] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [memKey, setMemKey] = useState("");
  const [memValue, setMemValue] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function create() {
    if (!name.trim()) return;
    try {
      await api.createContact(name.trim());
      setName("");
      onChanged();
    } catch {
      setError("Could not create that contact.");
    }
  }

  async function remove(id: string, label: string) {
    if (!confirm(`Delete ${label} and everything remembered about them? This cannot be undone.`)) {
      return;
    }
    await api.deleteContact(id);
    onChanged();
  }

  async function addMemory(id: string) {
    if (!memKey.trim() || !memValue.trim()) return;
    await api.addMemory(id, memKey.trim(), memValue.trim());
    setMemKey("");
    setMemValue("");
    onChanged();
  }

  return (
    <div className="space-y-4">
      <Card
        title="Contacts"
        subtitle="A separate memory for each conversation. Nothing is shared between them."
      >
        <div className="flex flex-wrap gap-2">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && create()}
            placeholder="Name or nickname"
            className="flex-1 rounded-xl border border-line bg-void/50 px-3 py-2 text-sm placeholder:text-muted/60"
          />
          <Button variant="primary" onClick={create} disabled={!name.trim()}>
            Add contact
          </Button>
        </div>
        {error && <p className="mt-2 text-xs text-danger">{error}</p>}
      </Card>

      {contacts.length === 0 ? (
        <Empty>No contacts yet.</Empty>
      ) : (
        contacts.map((c) => (
          <Card
            key={c.id}
            title={c.nickname || c.name}
            subtitle={
              c.observed_avg_length
                ? `She writes about ${c.observed_avg_length} words a message`
                : "Style will be observed as you analyse conversations"
            }
            right={
              <div className="flex gap-1.5">
                <Button size="sm" onClick={() => onOpenWorkspace(c.id)}>
                  Open
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setOpen(open === c.id ? null : c.id)}
                >
                  {open === c.id ? "Close" : "Details"}
                </Button>
              </div>
            }
          >
            {c.stated_boundaries.length > 0 && (
              <div className="mb-3 rounded-xl border border-danger/30 bg-danger/5 p-3">
                <p className="text-xs font-semibold text-danger">Boundaries she stated</p>
                {c.stated_boundaries.map((b, i) => (
                  <p key={i} className="mt-1 text-[11px] italic text-muted">
                    “{b}”
                  </p>
                ))}
                <p className="mt-1.5 text-[11px] text-muted">
                  Kept so the app keeps respecting it in future sessions.
                </p>
              </div>
            )}

            {c.memories.length === 0 ? (
              <p className="text-xs text-muted">Nothing remembered yet.</p>
            ) : (
              <ul className="space-y-1.5">
                {c.memories.map((m) => (
                  <li
                    key={m.id}
                    className="flex items-start justify-between gap-3 rounded-xl bg-raised px-3 py-2"
                  >
                    <div className="min-w-0">
                      <p className="text-[11px] text-muted">{m.key}</p>
                      <p className="break-words text-sm">{m.value}</p>
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      {m.confidence === "unconfirmed" && <Pill tone="warn">unconfirmed</Pill>}
                      <button
                        onClick={async () => {
                          await api.deleteMemory(c.id, m.id);
                          onChanged();
                        }}
                        className="text-[11px] text-muted hover:text-danger"
                      >
                        delete
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {open === c.id && (
              <div className="mt-4 space-y-2 border-t border-line pt-3">
                <p className="text-xs font-medium">Add something to remember</p>
                <div className="flex flex-wrap gap-2">
                  <input
                    value={memKey}
                    onChange={(e) => setMemKey(e.target.value)}
                    placeholder="e.g. person:randy"
                    className="w-44 rounded-lg border border-line bg-void/50 px-2.5 py-1.5 text-xs"
                  />
                  <input
                    value={memValue}
                    onChange={(e) => setMemValue(e.target.value)}
                    placeholder="Her cousin, works in Nakuru"
                    className="flex-1 rounded-lg border border-line bg-void/50 px-2.5 py-1.5 text-xs"
                  />
                  <Button size="sm" onClick={() => addMemory(c.id)}>
                    Save
                  </Button>
                </div>
                <Button
                  size="sm"
                  variant="danger"
                  onClick={() => remove(c.id, c.nickname || c.name)}
                >
                  Delete this contact
                </Button>
              </div>
            )}
          </Card>
        ))
      )}
    </div>
  );
}
