import type { TabId } from "../App";
import { Button, Card, Empty, Pill } from "../components/ui";
import type { ContactProfile, Health, StyleProfile } from "../lib/types";

export default function Dashboard({
  contacts,
  style,
  health,
  onOpenWorkspace,
  onGo,
}: {
  contacts: ContactProfile[];
  style: StyleProfile | null;
  health: Health | null;
  onOpenWorkspace: (contactId: string | null) => void;
  onGo: (tab: TabId) => void;
}) {
  const languageLabel = !style
    ? "-"
    : style.sheng_ratio >= 0.7
      ? "Heavy Sheng"
      : style.sheng_ratio >= 0.35
        ? "Sheng + English"
        : style.sheng_ratio >= 0.12
          ? "Mostly English"
          : "English";

  return (
    <div className="space-y-4">
      <Card className="bg-gradient-to-br from-accent-soft/25 to-transparent">
        <h2 className="text-xl font-semibold">Start a conversation workspace</h2>
        <p className="mt-1 max-w-xl text-sm leading-relaxed text-muted">
          Paste a conversation, mark who said what, pick what you are trying to do, and
          get replies that sound like you. When something comes up that only you could
          know, you will be asked instead of guessed at.
        </p>
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="primary" onClick={() => onOpenWorkspace(null)}>
            New conversation
          </Button>
          <Button onClick={() => onGo("style")}>Tune my style</Button>
        </div>
      </Card>

      <div className="grid gap-4 sm:grid-cols-3">
        <Card title="Your style">
          {style ? (
            <>
              <p className="text-lg font-semibold">{languageLabel}</p>
              <p className="mt-1 text-xs leading-relaxed text-muted">
                ~{style.avg_message_length} words a message · {style.humor_style}
              </p>
              <p className="mt-2 text-[11px] text-muted">
                {style.learned_from_messages === 0
                  ? "Nothing learned yet -- defaults in use."
                  : `Learned from ${style.learned_from_messages} of your messages.`}
              </p>
            </>
          ) : (
            <Empty>Not loaded.</Empty>
          )}
        </Card>

        <Card title="Contacts">
          <p className="text-lg font-semibold">{contacts.length}</p>
          <p className="mt-1 text-xs text-muted">
            {contacts.reduce((n, c) => n + c.memories.length, 0)} remembered details
          </p>
          <Button size="sm" variant="ghost" onClick={() => onGo("contacts")}>
            Manage →
          </Button>
        </Card>

        <Card title="AI">
          <p className="text-lg font-semibold">{health?.is_mock ? "Offline" : "Live"}</p>
          <p className="mt-1 break-words text-xs text-muted">
            {health ? (health.model ?? health.provider) : "—"}
          </p>
          <Button size="sm" variant="ghost" onClick={() => onGo("settings")}>
            Configure →
          </Button>
        </Card>
      </div>

      <Card
        title="Recent conversations"
        subtitle="Contacts you have set up. Conversations themselves are never stored."
      >
        {contacts.length === 0 ? (
          <Empty>
            No contacts yet. You can use the workspace without one -- add a contact when
            you want the app to remember context between sessions.
          </Empty>
        ) : (
          <ul className="space-y-2">
            {contacts.map((c) => (
              <li
                key={c.id}
                className="flex items-center justify-between gap-3 rounded-xl border border-line bg-raised px-3 py-2.5"
              >
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium">{c.nickname || c.name}</p>
                  <p className="text-[11px] text-muted">
                    {c.memories.length} remembered ·{" "}
                    {c.observed_sheng_ratio != null
                      ? `she writes ${Math.round(c.observed_sheng_ratio * 100)}% Sheng`
                      : "style not observed yet"}
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  {c.stated_boundaries.length > 0 && <Pill tone="danger">boundary</Pill>}
                  <Button size="sm" onClick={() => onOpenWorkspace(c.id)}>
                    Open
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card title="What is actually built" subtitle="So you know where this stands.">
        <ul className="space-y-1.5 text-xs leading-relaxed text-muted">
          <li>
            <span className="text-good">Working:</span> pasting and labelling
            conversations, conversation analysis, personal-context alerts, the
            Continue/Stop/Wait decision, goodnight and next-morning suggestions, contact
            memory, style learning, feedback.
          </li>
          <li>
            <span className="text-warn">Mocked without an API key:</span> the reply text
            itself. Analysis and alerts are real either way.
          </li>
          <li>
            <span className="text-muted">Not built yet:</span> accounts and cloud sync,
            the screenshot/example library, and anything on Android.
          </li>
        </ul>
      </Card>
    </div>
  );
}
