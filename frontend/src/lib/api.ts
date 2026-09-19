/**
 * The only place that talks to the backend.
 *
 * Requests go to a relative /api path, which Vite proxies to the FastAPI server
 * in development. No API keys ever reach this bundle -- the backend holds them.
 *
 * When accounts are configured, every request carries the signed-in user's
 * Supabase access token. The backend passes that same token to Postgres, so Row
 * Level Security -- not this file, and not the backend -- decides what it can
 * reach.
 */

import { getAccessToken } from "./auth";

import type {
  AnalyzeResponse,
  ContactProfile,
  Health,
  Memory,
  Message,
  ParseResponse,
  Recommendation,
  StyleProfile,
  SuggestResponse,
} from "./types";

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
  }

  /** True when the fix is to sign in again, rather than to retry. */
  get isAuthError() {
    return this.status === 401;
  }
}

/** Called when the backend rejects our token, so the UI can show the sign-in screen. */
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = await getAccessToken();
  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(init?.headers ?? {}),
      },
    });
  } catch {
    throw new ApiError(
      "Cannot reach the backend. Is it running on port 8000?",
      0,
    );
  }

  if (!response.ok) {
    let detail = `Request failed (${response.status})`;
    try {
      const body = await response.json();
      if (typeof body.detail === "string") detail = body.detail;
      else if (Array.isArray(body.detail) && body.detail[0]?.msg) {
        detail = body.detail[0].msg;
      }
    } catch {
      /* keep the generic message */
    }
    if (response.status === 401) onUnauthorized?.();
    throw new ApiError(detail, response.status);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

const post = <T,>(path: string, body: unknown) =>
  request<T>(path, { method: "POST", body: JSON.stringify(body) });

export const api = {
  health: () => request<Health>("/api/health"),

  goals: () => request<Record<string, string>>("/api/conversation/goals"),

  parse: (raw_text: string, me_label: string, them_label: string) =>
    post<ParseResponse>("/api/conversation/parse", { raw_text, me_label, them_label }),

  analyze: (messages: Message[], contact_id?: string | null) =>
    post<AnalyzeResponse>("/api/conversation/analyze", {
      messages,
      contact_id: contact_id ?? null,
      local_time: new Date().toISOString(),
    }),

  suggest: (payload: {
    messages: Message[];
    goal: string;
    contact_id?: string | null;
    supplied_context?: Record<string, string>;
    avoid?: string[];
    action?: Recommendation | null;
  }) =>
    post<SuggestResponse>("/api/conversation/suggest", {
      ...payload,
      contact_id: payload.contact_id ?? null,
      local_time: new Date().toISOString(),
    }),

  feedback: (payload: {
    suggestion_id: string;
    suggestion_text: string;
    verdict: "used" | "edited" | "rejected";
    note?: string;
  }) => post<void>("/api/conversation/feedback", payload),

  getStyle: () => request<StyleProfile>("/api/style"),
  getStyleBrief: () => request<{ brief: string }>("/api/style/brief"),
  updateStyle: (patch: Partial<StyleProfile>) =>
    request<StyleProfile>("/api/style", { method: "PATCH", body: JSON.stringify(patch) }),
  learnStyle: (messages: Message[]) => post<StyleProfile>("/api/style/learn", messages),
  resetStyle: () => post<StyleProfile>("/api/style/reset", {}),

  contacts: () => request<ContactProfile[]>("/api/contacts"),
  createContact: (name: string, nickname?: string) =>
    post<ContactProfile>("/api/contacts", { name, nickname: nickname || null, notes: "" }),
  updateContact: (id: string, patch: Partial<ContactProfile>) =>
    request<ContactProfile>(`/api/contacts/${id}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  deleteContact: (id: string) =>
    request<void>(`/api/contacts/${id}`, { method: "DELETE" }),
  addMemory: (contactId: string, key: string, value: string) =>
    post<Memory>(`/api/contacts/${contactId}/memories`, { key, value, source: "user" }),
  deleteMemory: (contactId: string, memoryId: string) =>
    request<void>(`/api/contacts/${contactId}/memories/${memoryId}`, { method: "DELETE" }),

  wipeAllData: () => request<void>("/api/data", { method: "DELETE" }),
};
