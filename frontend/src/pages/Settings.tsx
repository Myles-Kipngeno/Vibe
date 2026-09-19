import { useState } from "react";

import { Banner, Button, Card, Pill } from "../components/ui";
import { api } from "../lib/api";
import type { Health } from "../lib/types";

export default function Settings({
  health,
  onChanged,
}: {
  health: Health | null;
  onChanged: () => void;
}) {
  const [wiped, setWiped] = useState(false);

  async function wipe() {
    if (
      !confirm(
        "Delete your style profile, every contact and every remembered detail? This cannot be undone.",
      )
    ) {
      return;
    }
    await api.wipeAllData();
    setWiped(true);
    onChanged();
  }

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card title="AI provider" subtitle="Configured on the backend, never in the browser.">
        <div className="space-y-1.5 text-sm">
          <div className="flex justify-between">
            <span className="text-muted">Provider</span>
            <span>{health?.provider ?? "—"}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted">Model</span>
            <span className="text-right break-all">{health?.model ?? "—"}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-muted">Mode</span>
            {health?.is_mock ? <Pill tone="warn">offline templates</Pill> : <Pill tone="good">live</Pill>}
          </div>
        </div>

        {health?.is_mock && (
          <div className="mt-4 space-y-2">
            <Banner tone="accent">
              To turn on real generation:
              <ol className="mt-1.5 list-decimal space-y-0.5 pl-4">
                <li>
                  Get a key at{" "}
                  <span className="underline">console.anthropic.com/settings/keys</span>
                </li>
                <li>
                  Copy <code>backend/.env.example</code> to <code>backend/.env</code>
                </li>
                <li>
                  Set <code>ANTHROPIC_API_KEY=sk-ant-…</code>
                </li>
                <li>Restart the backend</li>
              </ol>
            </Banner>
            <p className="text-[11px] leading-relaxed text-muted">
              The key stays on the backend. It is never sent to this page and{" "}
              <code>.env</code> is git-ignored.
            </p>
          </div>
        )}
      </Card>

      <Card title="Privacy" subtitle="What leaves this machine, and what does not.">
        <ul className="space-y-2 text-xs leading-relaxed text-muted">
          {health?.notes.map((note, i) => (
            <li key={i}>• {note}</li>
          ))}
          <li>• Conversations themselves are never written to disk.</li>
          <li>
            • Stored on disk: your style profile, contacts, and the context you chose to
            remember. Nothing else.
          </li>
          <li>• Message bodies are kept out of the server logs.</li>
          <li>
            • Nothing you write here is used to train any model. Feedback stays local.
          </li>
        </ul>
      </Card>

      <Card title="Alerts" subtitle="Which interruptions are worth it.">
        <ul className="space-y-2 text-xs leading-relaxed text-muted">
          <li>
            <Pill tone="warn">stops generation</Pill> Missing personal context, an inside
            joke, or a personal question — the app asks rather than inventing an answer.
          </li>
          <li>
            <Pill tone="warn">needs you</Pill> A picture request, an emotional shift, or a
            boundary — you decide, not the app.
          </li>
          <li>
            <Pill>quiet</Pill> Ambiguous replies are shown alongside the analysis and
            never block anything.
          </li>
        </ul>
        <p className="mt-3 text-[11px] text-muted">
          Per-alert on/off switches and Android vibration come with the mobile phase.
        </p>
      </Card>

      <Card title="Your data">
        <p className="text-xs leading-relaxed text-muted">
          Deleting removes your style profile, every contact and every remembered detail
          from this machine. It cannot be undone.
        </p>
        <div className="mt-3 flex items-center gap-3">
          <Button variant="danger" onClick={wipe}>
            Delete everything
          </Button>
          {wiped && <span className="text-xs text-good">Deleted.</span>}
        </div>
      </Card>

      <Card title="Mobile" subtitle="Not built yet -- here is the honest position.">
        <ul className="space-y-2 text-xs leading-relaxed text-muted">
          <li>
            • A share-to-assistant flow (share a conversation into this app) is the
            realistic first Android step and is officially supported.
          </li>
          <li>
            • Reading WhatsApp or Instagram messages in the background needs
            notification-listener access, which Google restricts heavily, and neither
            platform offers an API for personal DMs.
          </li>
          <li>
            • Auto-sending messages on your behalf is not supported by either platform and
            is not planned.
          </li>
          <li>
            • A custom keyboard is technically possible and would need its own security
            review before it ever sees your conversations.
          </li>
        </ul>
      </Card>
    </div>
  );
}
