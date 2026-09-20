import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";

import { Banner, Button, Card, Empty, Pill } from "../components/ui";
import { api } from "../lib/api";
import type {
  ConversationExample,
  ConversationExampleCreate,
  LanguageMix,
} from "../lib/types";

const MIX_LABELS: Record<LanguageMix, string> = {
  english: "English",
  light_sheng: "Mostly English",
  mixed: "Sheng + English",
  heavy_sheng: "Heavy Sheng",
};

const BLANK: ConversationExampleCreate = {
  situation: "",
  context: "",
  opening_line: "",
  language_mix: "mixed",
  tone: "",
  reaction: "",
  what_worked: "",
  what_did_not: "",
  not_suitable_when: "",
};

const field =
  "w-full rounded-xl border border-line bg-void/50 px-3 py-2 text-sm placeholder:text-muted/60";

function Labelled({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-xs font-medium">{label}</span>
      {hint && (
        <span className="mt-0.5 block text-[11px] leading-relaxed text-muted">{hint}</span>
      )}
      <div className="mt-1.5">{children}</div>
    </label>
  );
}

/** The example library: conversations of yours worth remembering.
 *
 * The form is written to keep the library what it is meant to be. There is no
 * field for her messages, and the one that describes what came back asks for a
 * description rather than a quote -- the library is yours to keep and her words
 * are not yours to store.
 */
export default function Library() {
  const [examples, setExamples] = useState<ConversationExample[]>([]);
  const [draft, setDraft] = useState<ConversationExampleCreate>(BLANK);
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      setExamples(await api.examples());
    } catch {
      setError("Could not load the library.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  function set<K extends keyof ConversationExampleCreate>(
    key: K,
    value: ConversationExampleCreate[K],
  ) {
    setDraft((d) => ({ ...d, [key]: value }));
  }

  async function save() {
    if (!draft.situation.trim()) return;
    setError(null);
    try {
      await api.createExample({ ...draft, situation: draft.situation.trim() });
      setDraft(BLANK);
      setAdding(false);
      await load();
    } catch {
      setError("Could not save that example.");
    }
  }

  async function remove(example: ConversationExample) {
    if (!confirm(`Delete "${example.situation}"? This cannot be undone.`)) return;
    try {
      await api.deleteExample(example.id);
      await load();
    } catch {
      setError("Could not delete that example.");
    }
  }

  return (
    <div className="space-y-4">
      <Card
        title="Example library"
        subtitle="Conversations of yours that went well, kept as reference."
        right={
          !adding && (
            <Button size="sm" variant="primary" onClick={() => setAdding(true)}>
              Add an example
            </Button>
          )
        }
      >
        <p className="text-xs leading-relaxed text-muted">
          These are shown to the model for register and approach, never to be reused as
          lines &mdash; a line that worked on someone else is not a line, it is a
          coincidence. Most conversations will match nothing here, and that is the
          intended behaviour: at most two are ever shown, none at all once she has set a
          boundary, and none that you marked wrong for the moment you are in.
        </p>
        <div className="mt-3">
          <Banner tone="accent">
            Write what <strong>you</strong> sent and describe what came back. There is
            nowhere to put her messages, on purpose: this library is yours to keep and her
            words are not yours to store.
          </Banner>
        </div>
        {error && <p className="mt-2 text-xs text-danger">{error}</p>}
      </Card>

      {adding && (
        <Card title="New example">
          <div className="space-y-3">
            <Labelled
              label="The situation"
              hint="What kind of moment was it? This is what it gets matched on, so name the situation rather than the person."
            >
              <input
                value={draft.situation}
                onChange={(e) => set("situation", e.target.value)}
                placeholder="Opening a chat with someone from class"
                autoFocus
                className={field}
              />
            </Labelled>

            <Labelled label="Anything worth knowing" hint="Optional.">
              <input
                value={draft.context}
                onChange={(e) => set("context", e.target.value)}
                placeholder="We had spoken once before, briefly"
                className={field}
              />
            </Labelled>

            <Labelled label="What you wrote">
              <textarea
                value={draft.opening_line}
                onChange={(e) => set("opening_line", e.target.value)}
                rows={2}
                placeholder="Niaje, that lecture was rough"
                className={field + " resize-y"}
              />
            </Labelled>

            <div className="grid gap-3 sm:grid-cols-2">
              <Labelled label="Language">
                <select
                  value={draft.language_mix}
                  onChange={(e) => set("language_mix", e.target.value as LanguageMix)}
                  className={field}
                >
                  {(Object.keys(MIX_LABELS) as LanguageMix[]).map((mix) => (
                    <option key={mix} value={mix}>
                      {MIX_LABELS[mix]}
                    </option>
                  ))}
                </select>
              </Labelled>
              <Labelled label="Tone" hint="Optional.">
                <input
                  value={draft.tone}
                  onChange={(e) => set("tone", e.target.value)}
                  placeholder="friendly"
                  className={field}
                />
              </Labelled>
            </div>

            <Labelled
              label="What came back"
              hint="Describe it -- how quickly, how warmly -- rather than quoting her."
            >
              <input
                value={draft.reaction}
                onChange={(e) => set("reaction", e.target.value)}
                placeholder="Replied within a minute and asked something back"
                className={field}
              />
            </Labelled>

            <div className="grid gap-3 sm:grid-cols-2">
              <Labelled label="Why it worked" hint="Optional.">
                <input
                  value={draft.what_worked}
                  onChange={(e) => set("what_worked", e.target.value)}
                  placeholder="It named something we both sat through"
                  className={field}
                />
              </Labelled>
              <Labelled label="What fell flat" hint="Optional, and unusually useful.">
                <input
                  value={draft.what_did_not}
                  onChange={(e) => set("what_did_not", e.target.value)}
                  placeholder="Two questions at once got a one word reply"
                  className={field}
                />
              </Labelled>
            </div>

            <Labelled
              label="When this would be the wrong move"
              hint="The most important field here. An example that worked once is not a rule, and this is what stops it being used as one."
            >
              <input
                value={draft.not_suitable_when}
                onChange={(e) => set("not_suitable_when", e.target.value)}
                placeholder="when she is already quiet, or the conversation is serious"
                className={field}
              />
            </Labelled>

            <div className="flex flex-wrap items-center gap-2 pt-1">
              <Button
                variant="primary"
                onClick={save}
                disabled={!draft.situation.trim()}
                title={draft.situation.trim() ? undefined : "A situation is required"}
              >
                Save example
              </Button>
              <Button
                variant="ghost"
                onClick={() => {
                  setAdding(false);
                  setDraft(BLANK);
                  setError(null);
                }}
              >
                Cancel
              </Button>
            </div>
          </div>
        </Card>
      )}

      {loading ? (
        <Empty>Loading&hellip;</Empty>
      ) : examples.length === 0 ? (
        <Empty>
          Nothing saved yet. When a conversation of yours goes well, put it here &mdash;
          what the situation was, what you sent, and when it would be the wrong move.
        </Empty>
      ) : (
        <div className="space-y-3">
          {examples.map((example) => (
            <Card key={example.id}>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <h3 className="text-sm font-semibold">{example.situation}</h3>
                  {example.context && (
                    <p className="mt-0.5 text-xs text-muted">{example.context}</p>
                  )}
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  <Pill>{MIX_LABELS[example.language_mix]}</Pill>
                  <Button size="sm" variant="ghost" onClick={() => remove(example)}>
                    Delete
                  </Button>
                </div>
              </div>

              {example.opening_line && (
                <p className="mt-2.5 whitespace-pre-wrap rounded-xl bg-void/40 p-2.5 text-sm leading-relaxed">
                  {example.opening_line}
                </p>
              )}

              <dl className="mt-2 space-y-1 text-[11px] leading-relaxed text-muted">
                {example.reaction && (
                  <div>
                    <dt className="inline font-medium">What came back: </dt>
                    <dd className="inline">{example.reaction}</dd>
                  </div>
                )}
                {example.what_worked && (
                  <div>
                    <dt className="inline font-medium">Why it worked: </dt>
                    <dd className="inline">{example.what_worked}</dd>
                  </div>
                )}
                {example.what_did_not && (
                  <div>
                    <dt className="inline font-medium">What fell flat: </dt>
                    <dd className="inline">{example.what_did_not}</dd>
                  </div>
                )}
              </dl>

              {example.not_suitable_when ? (
                <p className="mt-2 rounded-xl border border-warn/30 bg-warn/5 px-2.5 py-1.5 text-[11px] leading-relaxed text-warn">
                  Not when: {example.not_suitable_when}
                </p>
              ) : (
                <p className="mt-2 text-[11px] leading-relaxed text-muted">
                  You have not said when this would be the wrong move, so it can be
                  matched to any moment of this kind.
                </p>
              )}
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
