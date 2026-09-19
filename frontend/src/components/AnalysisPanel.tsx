import type { Analysis } from "../lib/types";
import { Card, Pill } from "./ui";

const ENGAGEMENT_TONE = {
  engaged: "good",
  neutral: "neutral",
  cooling: "warn",
  disengaged: "danger",
} as const;

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5">
      <span className="text-xs text-muted">{label}</span>
      <span className="text-right text-xs">{children}</span>
    </div>
  );
}

/** Shows what was measured -- and, just as importantly, how sure we are. */
export default function AnalysisPanel({ analysis }: { analysis: Analysis }) {
  return (
    <Card title="What I can tell" subtitle={analysis.summary}>
      <div className="divide-y divide-line">
        <Row label="Topic">{analysis.topic}</Row>
        <Row label="Energy">
          <Pill tone="accent">{analysis.tone}</Pill>{" "}
          <span className="text-muted">({analysis.tone_confidence} confidence)</span>
        </Row>
        <Row label="Her engagement">
          <Pill tone={ENGAGEMENT_TONE[analysis.engagement]}>{analysis.engagement}</Pill>{" "}
          <span className="text-muted">({analysis.engagement_confidence})</span>
        </Row>
        <Row label="Thread">{analysis.flow_state.replace(/_/g, " ")}</Row>
        {analysis.time_of_day && <Row label="Time of day">{analysis.time_of_day}</Row>}
      </div>

      {analysis.boundary_detected && (
        <div className="mt-3 rounded-xl border border-danger/40 bg-danger/5 p-3">
          <p className="text-xs font-semibold text-danger">She set a boundary</p>
          {analysis.boundary_quotes.map((q, i) => (
            <p key={i} className="mt-1 text-[11px] italic text-muted">
              “{q}”
            </p>
          ))}
          <p className="mt-1.5 text-[11px] leading-relaxed text-muted">
            I will not write anything designed to talk her out of it.
          </p>
        </div>
      )}

      {analysis.open_questions.length > 0 && (
        <div className="mt-3">
          <p className="mb-1 text-xs font-medium">Still unanswered</p>
          <ul className="space-y-1">
            {analysis.open_questions.map((q, i) => (
              <li key={i} className="text-[11px] text-muted">
                <span className="text-accent">
                  {q.asked_by === "me" ? "you" : "her"}:
                </span>{" "}
                {q.text}
              </li>
            ))}
          </ul>
        </div>
      )}

      {analysis.uncertainty_notes.length > 0 && (
        <div className="mt-3 rounded-xl border border-line bg-void/40 p-3">
          <p className="mb-1 text-xs font-medium">What I do not know</p>
          <ul className="space-y-1">
            {analysis.uncertainty_notes.map((note, i) => (
              <li key={i} className="text-[11px] leading-relaxed text-muted">
                • {note}
              </li>
            ))}
          </ul>
        </div>
      )}
    </Card>
  );
}
