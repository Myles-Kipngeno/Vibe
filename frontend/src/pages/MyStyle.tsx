import { useEffect, useState } from "react";

import { Button, Card, Empty, Pill, Slider } from "../components/ui";
import { api } from "../lib/api";
import type { StyleProfile, Tone } from "../lib/types";

const TONES: Tone[] = ["friendly", "playful", "flirtatious", "suggestive"];

export default function MyStyle({
  style,
  onChanged,
}: {
  style: StyleProfile | null;
  onChanged: () => void;
}) {
  const [draft, setDraft] = useState<StyleProfile | null>(style);
  const [brief, setBrief] = useState("");
  const [note, setNote] = useState<string | null>(null);
  const [learnText, setLearnText] = useState("");

  useEffect(() => setDraft(style), [style]);
  useEffect(() => {
    api.getStyleBrief().then((r) => setBrief(r.brief)).catch(() => setBrief(""));
  }, [style]);

  if (!draft) return <Empty>Loading your profile…</Empty>;

  const set = <K extends keyof StyleProfile>(key: K, value: StyleProfile[K]) =>
    setDraft({ ...draft, [key]: value });

  async function save() {
    if (!draft) return;
    await api.updateStyle({
      sheng_ratio: draft.sheng_ratio,
      emoji_frequency: draft.emoji_frequency,
      directness: draft.directness,
      avg_message_length: draft.avg_message_length,
      humor_style: draft.humor_style,
      flirting_style: draft.flirting_style,
      max_tone: draft.max_tone,
    });
    setNote("Saved.");
    onChanged();
    setTimeout(() => setNote(null), 2000);
  }

  async function learn() {
    const lines = learnText.split("\n").map((l) => l.trim()).filter(Boolean);
    if (!lines.length) return;
    await api.learnStyle(lines.map((text) => ({ speaker: "me" as const, text })));
    setLearnText("");
    setNote("Learned from those messages.");
    onChanged();
    setTimeout(() => setNote(null), 2500);
  }

  async function reset() {
    if (!confirm("Reset your style profile back to defaults?")) return;
    await api.resetStyle();
    onChanged();
  }

  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card
        title="How you text"
        subtitle="Change anything here -- your edits are never overwritten wholesale by learning."
        right={
          <Pill tone={draft.learned_from_messages > 0 ? "good" : "neutral"}>
            {draft.learned_from_messages} messages learned
          </Pill>
        }
      >
        <div className="space-y-4">
          <Slider
            label="Sheng / English mix"
            value={draft.sheng_ratio}
            onChange={(v) => set("sheng_ratio", v)}
            left="All English"
            right="Heavy Sheng"
          />
          <Slider
            label="Emojis"
            value={draft.emoji_frequency}
            onChange={(v) => set("emoji_frequency", v)}
            left="Never"
            right="Most messages"
          />
          <Slider
            label="Directness"
            value={draft.directness}
            onChange={(v) => set("directness", v)}
            left="Subtle"
            right="Says it straight"
          />

          <label className="block">
            <span className="mb-1 block text-xs font-medium">
              Typical message length: {draft.avg_message_length} words
            </span>
            <input
              type="range"
              min={2}
              max={40}
              value={draft.avg_message_length}
              onChange={(e) => set("avg_message_length", Number(e.target.value))}
              className="w-full accent-accent"
            />
          </label>

          <label className="block">
            <span className="mb-1 block text-xs font-medium">Humour</span>
            <input
              value={draft.humor_style}
              onChange={(e) => set("humor_style", e.target.value)}
              className="w-full rounded-xl border border-line bg-void/50 px-3 py-2 text-sm"
            />
          </label>

          <label className="block">
            <span className="mb-1 block text-xs font-medium">Flirting style</span>
            <input
              value={draft.flirting_style}
              onChange={(e) => set("flirting_style", e.target.value)}
              className="w-full rounded-xl border border-line bg-void/50 px-3 py-2 text-sm"
            />
          </label>

          <div>
            <span className="mb-1.5 block text-xs font-medium">
              How far suggestions may go
            </span>
            <div className="flex flex-wrap gap-1.5">
              {TONES.map((t) => (
                <button
                  key={t}
                  onClick={() => set("max_tone", t)}
                  className={
                    "rounded-lg border px-2.5 py-1 text-xs transition " +
                    (draft.max_tone === t
                      ? "border-accent bg-accent/15 text-accent"
                      : "border-line text-muted hover:text-ink")
                  }
                >
                  {t}
                </button>
              ))}
            </div>
            <p className="mt-1.5 text-[11px] leading-relaxed text-muted">
              This caps the energy of every suggestion. Explicit content is never
              generated at any setting.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3">
            <Button variant="primary" onClick={save}>
              Save
            </Button>
            <Button variant="danger" onClick={reset}>
              Reset profile
            </Button>
            {note && <span className="text-xs text-good">{note}</span>}
          </div>
        </div>
      </Card>

      <div className="space-y-4">
        <Card
          title="Teach it your voice"
          subtitle="Paste messages you actually sent, one per line. Only your own."
        >
          <textarea
            value={learnText}
            onChange={(e) => setLearnText(e.target.value)}
            rows={6}
            placeholder={"niaje manze\nhaha hiyo ni noma\nsi tuonane weekend"}
            className="w-full resize-y rounded-xl border border-line bg-void/50 p-3 font-mono text-sm placeholder:text-muted/60"
          />
          <Button
            variant="primary"
            size="sm"
            onClick={learn}
            disabled={!learnText.trim()}
          >
            Learn from these
          </Button>
        </Card>

        {draft.common_expressions.length > 0 && (
          <Card title="Expressions you repeat">
            <div className="flex flex-wrap gap-1.5">
              {draft.common_expressions.map((e) => (
                <Pill key={e}>{e}</Pill>
              ))}
            </div>
          </Card>
        )}

        <Card
          title="What the AI is told about you"
          subtitle="The exact text sent with every request. No hidden prompt."
        >
          <pre className="overflow-x-auto whitespace-pre-wrap rounded-xl bg-void/50 p-3 text-[11px] leading-relaxed text-muted">
            {brief || "—"}
          </pre>
        </Card>
      </div>
    </div>
  );
}
