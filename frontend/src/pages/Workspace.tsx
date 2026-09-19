import { useEffect, useMemo, useState } from "react";

import AlertCard from "../components/AlertCard";
import AnalysisPanel from "../components/AnalysisPanel";
import SuggestionCard from "../components/SuggestionCard";
import { Banner, Button, Card, Empty, Pill } from "../components/ui";
import { ApiError, api } from "../lib/api";
import type {
  Analysis,
  ContactProfile,
  ContextAlert,
  Message,
  Recommendation,
  Suggestion,
} from "../lib/types";

const SAMPLE = `Her: Niaje, long time
Me: Haha ndio, nimekuwa busy na job. Wewe je?
Her: Niko poa. Nimekuwa nikienjoy hii break
Her: Ulienda town na Randy?`;

export default function Workspace({
  contacts,
  contactId,
  setContactId,
  isMock,
  onContactsChanged,
}: {
  contacts: ContactProfile[];
  contactId: string | null;
  setContactId: (id: string | null) => void;
  isMock: boolean;
  onContactsChanged: () => void;
}) {
  const [raw, setRaw] = useState("");
  const [meLabel, setMeLabel] = useState("Me");
  const [themLabel, setThemLabel] = useState("Her");
  const [messages, setMessages] = useState<Message[]>([]);
  const [parseNote, setParseNote] = useState<string | null>(null);

  const [goals, setGoals] = useState<Record<string, string>>({});
  const [goal, setGoal] = useState("keep_flowing");

  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);
  const [guidance, setGuidance] = useState<string | null>(null);
  const [blocked, setBlocked] = useState<string | null>(null);
  const [pending, setPending] = useState<ContextAlert[]>([]);
  const [supplied, setSupplied] = useState<Record<string, string>>({});
  const [shown, setShown] = useState<string[]>([]);

  const [busy, setBusy] = useState<"analyze" | "suggest" | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.goals().then(setGoals).catch(() => setGoals({}));
  }, []);

  const contact = useMemo(
    () => contacts.find((c) => c.id === contactId) ?? null,
    [contacts, contactId],
  );

  function reset() {
    setAnalysis(null);
    setSuggestions([]);
    setGuidance(null);
    setBlocked(null);
    setPending([]);
    setShown([]);
  }

  async function handleParse() {
    setError(null);
    try {
      const result = await api.parse(raw, meLabel, themLabel);
      setMessages(result.messages);
      reset();
      setParseNote(
        result.detected_format === "alternating_guess"
          ? "I could not tell who said what, so I alternated the lines starting with her. Check each one and flip any that are wrong."
          : result.detected_format === "whatsapp_export"
            ? "Read as a WhatsApp export, timestamps included."
            : "Read from the labels on each line.",
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not read that.");
    }
  }

  function flip(index: number) {
    setMessages((prev) =>
      prev.map((m, i) =>
        i === index ? { ...m, speaker: m.speaker === "me" ? "them" : "me" } : m,
      ),
    );
  }

  function removeMessage(index: number) {
    setMessages((prev) => prev.filter((_, i) => i !== index));
  }

  async function handleAnalyze() {
    if (!messages.length) return;
    setBusy("analyze");
    setError(null);
    try {
      const result = await api.analyze(messages, contactId);
      setAnalysis(result.analysis);
      setSuggestions([]);
      setBlocked(null);
      setPending(result.analysis.alerts.filter((a) => a.priority !== "low"));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Analysis failed.");
    } finally {
      setBusy(null);
    }
  }

  async function handleSuggest(action?: Recommendation) {
    if (!messages.length) return;
    setBusy("suggest");
    setError(null);
    try {
      const result = await api.suggest({
        messages,
        goal,
        contact_id: contactId,
        supplied_context: supplied,
        avoid: shown,
        action: action ?? null,
      });
      setSuggestions(result.suggestions);
      setGuidance(result.guidance ?? null);
      setBlocked(result.blocked ? (result.blocked_reason ?? "Blocked.") : null);
      if (result.unresolved_alerts.length) setPending(result.unresolved_alerts);
      setShown((prev) => [...prev, ...result.suggestions.map((s) => s.text)]);
      if (!analysis) await handleAnalyze();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not generate replies.");
    } finally {
      setBusy(null);
    }
  }

  async function answerAlert(alert: ContextAlert, answer: string, remember: boolean) {
    setSupplied((prev) => ({ ...prev, [alert.dedupe_key]: answer }));
    setPending((prev) => prev.filter((a) => a.dedupe_key !== alert.dedupe_key));
    if (remember && contactId) {
      try {
        await api.addMemory(contactId, alert.dedupe_key, answer);
        onContactsChanged();
      } catch {
        setError("Saved for this session, but could not store it on the contact.");
      }
    }
    setBlocked(null);
  }

  const blockingAlerts = pending.filter(
    (a) =>
      a.priority !== "low" &&
      ["personal_context", "inside_joke", "personal_question"].includes(a.kind),
  );
  const infoAlerts = pending.filter((a) => !blockingAlerts.includes(a));

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_380px]">
      <div className="space-y-4">
        {/* --- 1. Input ---------------------------------------------------- */}
        <Card
          title="The conversation"
          subtitle="Paste it, or type it out. Nothing is uploaded until you ask for replies."
          right={
            <select
              value={contactId ?? ""}
              onChange={(e) => setContactId(e.target.value || null)}
              className="rounded-lg border border-line bg-raised px-2 py-1 text-xs"
            >
              <option value="">No contact</option>
              {contacts.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.nickname || c.name}
                </option>
              ))}
            </select>
          }
        >
          <textarea
            value={raw}
            onChange={(e) => setRaw(e.target.value)}
            rows={7}
            placeholder={"Her: Niaje\nMe: Poa sana, wewe je\nHer: Niko fiti"}
            className="w-full resize-y rounded-xl border border-line bg-void/50 p-3 font-mono text-sm leading-relaxed placeholder:text-muted/60"
          />
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <label className="flex items-center gap-1 text-xs text-muted">
              You:
              <input
                value={meLabel}
                onChange={(e) => setMeLabel(e.target.value)}
                className="w-20 rounded-lg border border-line bg-raised px-2 py-1 text-xs text-ink"
              />
            </label>
            <label className="flex items-center gap-1 text-xs text-muted">
              Her:
              <input
                value={themLabel}
                onChange={(e) => setThemLabel(e.target.value)}
                className="w-20 rounded-lg border border-line bg-raised px-2 py-1 text-xs text-ink"
              />
            </label>
            <Button variant="primary" size="sm" onClick={handleParse} disabled={!raw.trim()}>
              Load conversation
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setRaw(SAMPLE)}>
              Use an example
            </Button>
          </div>
          {parseNote && <p className="mt-2 text-[11px] text-muted">{parseNote}</p>}
        </Card>

        {/* --- 2. Who said what -------------------------------------------- */}
        {messages.length > 0 && (
          <Card
            title="Who said what"
            subtitle="Tap a message to flip it. Getting this right matters -- everything else depends on it."
          >
            <ul className="space-y-1.5">
              {messages.map((m, i) => (
                <li
                  key={i}
                  className={
                    "group flex items-start gap-2 " +
                    (m.speaker === "me" ? "justify-end" : "justify-start")
                  }
                >
                  {m.speaker === "me" && (
                    <button
                      onClick={() => removeMessage(i)}
                      className="mt-1 text-[11px] text-muted opacity-0 transition group-hover:opacity-100"
                      title="Remove this message"
                    >
                      ✕
                    </button>
                  )}
                  <button
                    onClick={() => flip(i)}
                    title="Flip who said this"
                    className={
                      "max-w-[85%] rounded-2xl px-3 py-2 text-left text-sm leading-snug transition " +
                      (m.speaker === "me"
                        ? "rounded-br-sm bg-accent-soft/35 hover:bg-accent-soft/50"
                        : "rounded-bl-sm bg-raised hover:bg-line")
                    }
                  >
                    <span className="mb-0.5 block text-[10px] uppercase tracking-wider text-muted">
                      {m.speaker === "me" ? "you" : themLabel}
                    </span>
                    <span className="whitespace-pre-wrap">{m.text}</span>
                  </button>
                  {m.speaker === "them" && (
                    <button
                      onClick={() => removeMessage(i)}
                      className="mt-1 text-[11px] text-muted opacity-0 transition group-hover:opacity-100"
                      title="Remove this message"
                    >
                      ✕
                    </button>
                  )}
                </li>
              ))}
            </ul>

            <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-line pt-3">
              <label className="text-xs text-muted">Goal</label>
              <select
                value={goal}
                onChange={(e) => setGoal(e.target.value)}
                className="rounded-lg border border-line bg-raised px-2 py-1.5 text-xs"
              >
                {Object.entries(goals).map(([id, label]) => (
                  <option key={id} value={id}>
                    {label}
                  </option>
                ))}
              </select>
              <Button size="sm" onClick={handleAnalyze} disabled={busy !== null}>
                {busy === "analyze" ? "Reading…" : "Analyze"}
              </Button>
              <Button
                size="sm"
                variant="primary"
                onClick={() => handleSuggest()}
                disabled={busy !== null}
              >
                {busy === "suggest" ? "Writing…" : "Suggest replies"}
              </Button>
            </div>
          </Card>
        )}

        {error && <Banner tone="danger">{error}</Banner>}

        {/* --- 3. Alerts ---------------------------------------------------- */}
        {blockingAlerts.length > 0 && (
          <div className="space-y-3">
            {blockingAlerts.map((alert) => (
              <AlertCard
                key={alert.id}
                alert={alert}
                canRemember={Boolean(contactId)}
                onAnswer={answerAlert}
              />
            ))}
          </div>
        )}

        {blocked && blockingAlerts.length === 0 && <Banner tone="warn">{blocked}</Banner>}

        {/* --- 4. Suggestions ------------------------------------------------ */}
        {(suggestions.length > 0 || guidance) && (
          <Card
            title="Suggested replies"
            subtitle={isMock ? "Offline templates -- not AI output." : undefined}
            right={
              suggestions.length > 0 ? (
                <Button size="sm" variant="ghost" onClick={() => handleSuggest()}>
                  Regenerate
                </Button>
              ) : undefined
            }
          >
            {guidance && <p className="mb-3 text-xs leading-relaxed text-muted">{guidance}</p>}
            {suggestions.length === 0 ? (
              <Empty>Nothing to send right now.</Empty>
            ) : (
              <div className="space-y-3">
                {suggestions.map((s) => (
                  <SuggestionCard key={s.id} suggestion={s} />
                ))}
              </div>
            )}
          </Card>
        )}
      </div>

      {/* --- Right column: analysis, decision, context ---------------------- */}
      <div className="space-y-4">
        {analysis ? (
          <>
            <AnalysisPanel analysis={analysis} />

            <Card
              title="What now?"
              subtitle={`Suggested: ${analysis.recommendation}`}
            >
              <p className="mb-3 text-xs leading-relaxed text-muted">
                {analysis.recommendation_reason}
              </p>
              <div className="grid grid-cols-3 gap-2">
                {(["continue", "stop", "wait"] as const).map((action) => (
                  <Button
                    key={action}
                    size="sm"
                    variant={analysis.recommendation === action ? "primary" : "outline"}
                    onClick={() => handleSuggest(action)}
                    disabled={busy !== null}
                  >
                    {action[0].toUpperCase() + action.slice(1)}
                  </Button>
                ))}
              </div>
              <p className="mt-2 text-[11px] leading-relaxed text-muted">
                Continue drafts one good message. Stop helps you close it warmly. Wait
                sends nothing at all.
              </p>
            </Card>

            {infoAlerts.length > 0 && (
              <Card title="Worth your attention" subtitle="These do not block replies.">
                <ul className="space-y-2">
                  {infoAlerts.map((a) => (
                    <li key={a.id} className="rounded-xl border border-line bg-raised p-3">
                      <div className="mb-1 flex items-center gap-2">
                        <Pill tone={a.priority === "high" ? "warn" : "neutral"}>
                          {a.kind.replace(/_/g, " ")}
                        </Pill>
                      </div>
                      <p className="text-xs font-medium">{a.title}</p>
                      <p className="mt-1 text-[11px] italic text-muted">“{a.quote}”</p>
                      <p className="mt-1.5 text-[11px] text-muted">{a.question}</p>
                    </li>
                  ))}
                </ul>
              </Card>
            )}
          </>
        ) : (
          <Card title="Analysis">
            <Empty>
              Load a conversation and press Analyze. Everything on this side is computed
              locally.
            </Empty>
          </Card>
        )}

        {contact && contact.memories.length > 0 && (
          <Card title={`What I know about ${contact.nickname || contact.name}`}>
            <ul className="space-y-1.5 text-xs">
              {contact.memories.map((m) => (
                <li key={m.id} className="rounded-lg bg-raised px-2.5 py-1.5">
                  <span className="text-muted">{m.key}</span>
                  <br />
                  {m.value}
                </li>
              ))}
            </ul>
            <p className="mt-2 text-[11px] text-muted">
              Edit or delete these under Contacts.
            </p>
          </Card>
        )}
      </div>
    </div>
  );
}
