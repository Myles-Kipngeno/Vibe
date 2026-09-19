import { useState } from "react";

import { api } from "../lib/api";
import type { Suggestion } from "../lib/types";
import { Button, Pill } from "./ui";

/** One reply option: copy it, edit it, or tell the app it missed. */
export default function SuggestionCard({ suggestion }: { suggestion: Suggestion }) {
  const [text, setText] = useState(suggestion.text);
  const [editing, setEditing] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  const edited = text !== suggestion.text;

  async function send(verdict: "used" | "edited" | "rejected", label: string) {
    try {
      await api.feedback({
        suggestion_id: suggestion.id,
        suggestion_text: text,
        verdict,
      });
      setNote(label);
    } catch {
      setNote("Could not save that feedback.");
    }
    setTimeout(() => setNote(null), 2500);
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      setNote("Copied.");
      void send(edited ? "edited" : "used", "Copied.");
    } catch {
      setNote("Copy failed -- select the text and copy it manually.");
      setTimeout(() => setNote(null), 2500);
    }
  }

  return (
    <article className="rounded-2xl border border-line bg-raised p-3.5">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Pill tone="accent">{suggestion.approach}</Pill>
        {edited && <Pill>edited</Pill>}
      </div>

      {editing ? (
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={3}
          autoFocus
          onBlur={() => setEditing(false)}
          className="w-full resize-y rounded-xl border border-accent/40 bg-void/50 p-2.5 text-sm"
        />
      ) : (
        <p
          onClick={() => setEditing(true)}
          className="cursor-text whitespace-pre-wrap rounded-xl bg-void/40 p-2.5 text-sm leading-relaxed"
        >
          {text}
        </p>
      )}

      <p className="mt-2 text-[11px] leading-relaxed text-muted">{suggestion.rationale}</p>

      <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
        <Button size="sm" variant="primary" onClick={copy}>
          Copy
        </Button>
        <Button size="sm" onClick={() => setEditing(true)}>
          Edit
        </Button>
        <Button size="sm" variant="ghost" onClick={() => send("rejected", "Noted.")}>
          Not me
        </Button>
        {note && <span className="text-[11px] text-good">{note}</span>}
      </div>
    </article>
  );
}
