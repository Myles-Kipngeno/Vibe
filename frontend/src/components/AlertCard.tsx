import { useState } from "react";

import type { ContextAlert } from "../lib/types";
import { Button, Pill } from "./ui";

const ICONS: Record<string, string> = {
  personal_context: "🔔",
  inside_joke: "😅",
  personal_question: "🙋",
  picture_request: "📸",
  emotional_shift: "💛",
  ambiguous_message: "🤔",
  boundary_signal: "🛑",
};

/**
 * The Personal Context Alert.
 *
 * This is the interaction the product is built around: rather than inventing a
 * detail it cannot know, the assistant stops and asks one specific question.
 */
export default function AlertCard({
  alert,
  canRemember,
  onAnswer,
}: {
  alert: ContextAlert;
  canRemember: boolean;
  onAnswer: (alert: ContextAlert, answer: string, remember: boolean) => void;
}) {
  const [answer, setAnswer] = useState("");
  const [remember, setRemember] = useState(canRemember);

  return (
    <section className="rounded-2xl border border-accent/35 bg-accent/5 p-4">
      <header className="mb-2 flex items-center gap-2">
        <span className="text-base">{ICONS[alert.kind] ?? "🔔"}</span>
        <h3 className="text-sm font-semibold">{alert.title}</h3>
        {alert.priority === "high" && <Pill tone="warn">needs you</Pill>}
      </header>

      <blockquote className="mb-2 border-l-2 border-accent/40 pl-3 text-sm italic text-muted">
        “{alert.quote}”
      </blockquote>
      <p className="mb-3 text-sm leading-relaxed">{alert.question}</p>

      <textarea
        value={answer}
        onChange={(e) => setAnswer(e.target.value)}
        rows={2}
        placeholder="Type context…"
        className="w-full resize-y rounded-xl border border-line bg-void/50 p-2.5 text-sm placeholder:text-muted/60"
      />

      <div className="mt-2.5 flex flex-wrap items-center justify-between gap-2">
        <label className="flex items-center gap-2 text-xs text-muted">
          <input
            type="checkbox"
            checked={remember}
            disabled={!canRemember}
            onChange={(e) => setRemember(e.target.checked)}
            className="accent-accent"
          />
          {canRemember
            ? "Remember this for this contact"
            : "Pick a contact to remember this"}
        </label>
        <Button
          variant="primary"
          size="sm"
          disabled={!answer.trim()}
          onClick={() => onAnswer(alert, answer.trim(), remember && canRemember)}
        >
          Continue
        </Button>
      </div>
    </section>
  );
}
