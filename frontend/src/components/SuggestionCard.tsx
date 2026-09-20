import { useState } from "react";

import { api } from "../lib/api";
import type { Suggestion } from "../lib/types";
import { Button, Pill } from "./ui";

/** One reply option: copy it, edit it, or tell the app it missed.
 *
 * Rejecting is recorded the moment it is clicked, so walking away never loses
 * the verdict. The reason box that opens afterwards is optional and sends a
 * second verdict for the same suggestion; the backend keeps the later one, so
 * saying why replaces the bare rejection rather than counting twice.
 */
export default function SuggestionCard({ suggestion }: { suggestion: Suggestion }) {
  const [text, setText] = useState(suggestion.text);
  const [editing, setEditing] = useState(false);
  const [status, setStatus] = useState<string | null>(null);
  const [askingWhy, setAskingWhy] = useState(false);
  const [reason, setReason] = useState("");

  const edited = text !== suggestion.text;

  function flash(message: string) {
    setStatus(message);
    setTimeout(() => setStatus(null), 2500);
  }

  async function send(
    verdict: "used" | "edited" | "rejected",
    label: string,
    note = "",
  ) {
    try {
      await api.feedback({
        suggestion_id: suggestion.id,
        suggestion_text: text,
        verdict,
        note,
      });
      flash(label);
    } catch {
      flash("Could not save that feedback.");
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(text);
      void send(edited ? "edited" : "used", "Copied.");
    } catch {
      flash("Copy failed -- select the text and copy it manually.");
    }
  }

  function reject() {
    setAskingWhy(true);
    void send("rejected", "Noted.");
  }

  function sendReason() {
    const why = reason.trim();
    setAskingWhy(false);
    setReason("");
    if (why) void send("rejected", "Noted -- that helps.", why);
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
        <Button size="sm" variant="ghost" onClick={reject}>
          Not me
        </Button>
        {status && <span className="text-[11px] text-good">{status}</span>}
      </div>

      {askingWhy && (
        <div className="mt-2.5 rounded-xl border border-line bg-void/40 p-2.5">
          <label
            htmlFor={`why-${suggestion.id}`}
            className="block text-[11px] leading-relaxed text-muted"
          >
            What was off about it? One line is enough, and it goes to the model
            word for word next time. Optional.
          </label>
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <input
              id={`why-${suggestion.id}`}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              autoFocus
              placeholder="too corny, I don't talk like that"
              onKeyDown={(e) => {
                if (e.key === "Enter") sendReason();
                if (e.key === "Escape") {
                  setAskingWhy(false);
                  setReason("");
                }
              }}
              className="min-w-0 flex-1 rounded-lg border border-line bg-void/60 px-2.5 py-1.5 text-xs"
            />
            <Button size="sm" variant="primary" onClick={sendReason}>
              Save
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => {
                setAskingWhy(false);
                setReason("");
              }}
            >
              Skip
            </Button>
          </div>
        </div>
      )}
    </article>
  );
}
