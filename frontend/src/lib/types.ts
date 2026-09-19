/** Mirrors backend/app/schemas.py. Keep the two in sync when either changes. */

export type Speaker = "me" | "them";

export type AlertKind =
  | "personal_context"
  | "inside_joke"
  | "personal_question"
  | "picture_request"
  | "emotional_shift"
  | "ambiguous_message"
  | "boundary_signal";

export type AlertPriority = "low" | "normal" | "high";
export type Tone = "friendly" | "playful" | "flirtatious" | "suggestive" | "serious";
export type FlowState = "flowing" | "slowing_down" | "winding_down" | "stalled" | "ended";
export type Recommendation = "continue" | "stop" | "wait";
export type Confidence = "low" | "medium" | "high";

export interface Message {
  speaker: Speaker;
  text: string;
  sent_at?: string | null;
}

export interface ParseResponse {
  messages: Message[];
  unparsed_lines: string[];
  detected_format: string;
}

export interface ContextAlert {
  id: string;
  kind: AlertKind;
  priority: AlertPriority;
  title: string;
  quote: string;
  question: string;
  subject?: string | null;
  dedupe_key: string;
}

export interface OpenQuestion {
  text: string;
  asked_by: Speaker;
  message_index: number;
}

export interface Analysis {
  topic: string;
  tone: Tone;
  tone_confidence: Confidence;
  flow_state: FlowState;
  engagement: "engaged" | "neutral" | "cooling" | "disengaged";
  engagement_confidence: Confidence;
  summary: string;
  open_questions: OpenQuestion[];
  alerts: ContextAlert[];
  boundary_detected: boolean;
  boundary_quotes: string[];
  recommendation: Recommendation;
  recommendation_reason: string;
  time_of_day?: "morning" | "day" | "evening" | "night" | null;
  uncertainty_notes: string[];
  observed_their_style: Record<string, number>;
}

export interface AnalyzeResponse {
  analysis: Analysis;
  provider: string;
  is_mock: boolean;
}

export interface Suggestion {
  id: string;
  text: string;
  rationale: string;
  tone: Tone;
  approach: string;
}

export interface SuggestResponse {
  suggestions: Suggestion[];
  blocked: boolean;
  blocked_reason?: string | null;
  guidance?: string | null;
  unresolved_alerts: ContextAlert[];
  provider: string;
  is_mock: boolean;
}

export interface StyleProfile {
  sheng_ratio: number;
  avg_message_length: number;
  emoji_frequency: number;
  humor_style: string;
  directness: number;
  flirting_style: string;
  common_expressions: string[];
  example_messages: string[];
  max_tone: Tone;
  learned_from_messages: number;
  updated_at?: string | null;
}

export interface Memory {
  id: string;
  key: string;
  value: string;
  source: "user" | "conversation";
  confidence: "confirmed" | "unconfirmed";
  created_at: string;
}

export interface ContactProfile {
  id: string;
  name: string;
  nickname?: string | null;
  notes: string;
  memories: Memory[];
  observed_sheng_ratio?: number | null;
  observed_avg_length?: number | null;
  stated_boundaries: string[];
  created_at: string;
  updated_at: string;
}

export interface Health {
  status: string;
  provider: string;
  is_mock: boolean;
  model?: string | null;
  notes: string[];
  warnings: string[];
  auth_required: boolean;
  supabase_url?: string | null;
  /** The public anon key. RLS, not secrecy, is what protects the data. */
  supabase_anon_key?: string | null;
}
