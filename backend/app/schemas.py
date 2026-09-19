"""Pydantic request/response models.

These are the contract between the FastAPI backend and the React frontend.
Keeping them in one module makes it easy to keep the TypeScript types in
`frontend/src/lib/types.ts` in sync.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal, Optional

from pydantic import BaseModel, Field

Speaker = Literal["me", "them"]

AlertKind = Literal[
    "personal_context",
    "inside_joke",
    "personal_question",
    "picture_request",
    "emotional_shift",
    "ambiguous_message",
    "boundary_signal",
]

AlertPriority = Literal["low", "normal", "high"]

Tone = Literal["friendly", "playful", "flirtatious", "suggestive", "serious"]

FlowState = Literal["flowing", "slowing_down", "winding_down", "stalled", "ended"]

Recommendation = Literal["continue", "stop", "wait"]


# --- Messages -----------------------------------------------------------------


class Message(BaseModel):
    """One message in a conversation.

    `sent_at` is optional on purpose: pasted conversations often have no usable
    timestamps, and the rhythm engine must not pretend to know reply intervals
    it was never given.
    """

    speaker: Speaker
    text: str
    sent_at: Optional[datetime] = None


class ParseRequest(BaseModel):
    """Raw pasted text plus the labels used for each side."""

    raw_text: str
    me_label: str = "Me"
    them_label: str = "Them"


class ParseResponse(BaseModel):
    messages: list[Message]
    unparsed_lines: list[str] = Field(default_factory=list)
    detected_format: str


# --- Profiles -----------------------------------------------------------------


class StyleProfile(BaseModel):
    """How the user naturally texts. Every field is user-editable."""

    sheng_ratio: float = Field(0.4, ge=0.0, le=1.0, description="0 = pure English, 1 = heavy Sheng")
    avg_message_length: int = Field(9, ge=1, description="Words per message")
    emoji_frequency: float = Field(0.3, ge=0.0, le=1.0, description="Share of messages with an emoji")
    humor_style: str = "playful teasing"
    directness: float = Field(0.6, ge=0.0, le=1.0, description="0 = subtle, 1 = very direct")
    flirting_style: str = "light and teasing, not intense"
    common_expressions: list[str] = Field(default_factory=list)
    example_messages: list[str] = Field(default_factory=list)
    max_tone: Tone = "flirtatious"
    learned_from_messages: int = 0
    updated_at: Optional[datetime] = None


class StyleProfileUpdate(BaseModel):
    sheng_ratio: Optional[float] = Field(None, ge=0.0, le=1.0)
    avg_message_length: Optional[int] = Field(None, ge=1)
    emoji_frequency: Optional[float] = Field(None, ge=0.0, le=1.0)
    humor_style: Optional[str] = None
    directness: Optional[float] = Field(None, ge=0.0, le=1.0)
    flirting_style: Optional[str] = None
    common_expressions: Optional[list[str]] = None
    example_messages: Optional[list[str]] = None
    max_tone: Optional[Tone] = None


class Memory(BaseModel):
    """One remembered fact about a contact, always attributable and deletable."""

    id: str
    key: str
    value: str
    source: Literal["user", "conversation"] = "user"
    confidence: Literal["confirmed", "unconfirmed"] = "confirmed"
    created_at: datetime


class MemoryCreate(BaseModel):
    key: str
    value: str
    source: Literal["user", "conversation"] = "user"
    confidence: Literal["confirmed", "unconfirmed"] = "confirmed"


class ContactProfile(BaseModel):
    id: str
    name: str
    nickname: Optional[str] = None
    notes: str = ""
    memories: list[Memory] = Field(default_factory=list)
    observed_sheng_ratio: Optional[float] = None
    observed_avg_length: Optional[int] = None
    stated_boundaries: list[str] = Field(default_factory=list)
    created_at: datetime
    updated_at: datetime


class ContactCreate(BaseModel):
    name: str
    nickname: Optional[str] = None
    notes: str = ""


class ContactUpdate(BaseModel):
    name: Optional[str] = None
    nickname: Optional[str] = None
    notes: Optional[str] = None


# --- Analysis -----------------------------------------------------------------


class ContextAlert(BaseModel):
    """Something the assistant genuinely cannot answer on its own."""

    id: str
    kind: AlertKind
    priority: AlertPriority
    title: str
    quote: str = Field(description="The message fragment that triggered the alert")
    question: str = Field(description="The one thing we are asking the user")
    subject: Optional[str] = Field(None, description="Person/thing the alert is about")
    dedupe_key: str = Field(description="Stable key so we do not re-ask what is already remembered")


class OpenQuestion(BaseModel):
    text: str
    asked_by: Speaker
    message_index: int


class Analysis(BaseModel):
    topic: str
    tone: Tone
    tone_confidence: Literal["low", "medium", "high"]
    flow_state: FlowState
    engagement: Literal["engaged", "neutral", "cooling", "disengaged"]
    engagement_confidence: Literal["low", "medium", "high"]
    summary: str
    open_questions: list[OpenQuestion] = Field(default_factory=list)
    alerts: list[ContextAlert] = Field(default_factory=list)
    boundary_detected: bool = False
    boundary_quotes: list[str] = Field(default_factory=list)
    recommendation: Recommendation
    recommendation_reason: str
    time_of_day: Optional[Literal["morning", "day", "evening", "night"]] = None
    uncertainty_notes: list[str] = Field(default_factory=list)
    observed_their_style: dict[str, float] = Field(default_factory=dict)


class AnalyzeRequest(BaseModel):
    messages: list[Message]
    contact_id: Optional[str] = None
    local_time: Optional[datetime] = None


class AnalyzeResponse(BaseModel):
    analysis: Analysis
    provider: str
    is_mock: bool


# --- Suggestions --------------------------------------------------------------


class Suggestion(BaseModel):
    id: str
    text: str
    rationale: str
    tone: Tone
    approach: str


class SuggestRequest(BaseModel):
    messages: list[Message]
    goal: str = "keep_flowing"
    contact_id: Optional[str] = None
    supplied_context: dict[str, str] = Field(
        default_factory=dict,
        description="Answers to context alerts, keyed by alert dedupe_key",
    )
    avoid: list[str] = Field(
        default_factory=list, description="Previously shown suggestions not to repeat"
    )
    action: Optional[Recommendation] = Field(
        None, description="Set when the user picked Continue / Stop / Wait"
    )
    local_time: Optional[datetime] = None


class SuggestResponse(BaseModel):
    suggestions: list[Suggestion]
    blocked: bool = False
    blocked_reason: Optional[str] = None
    guidance: Optional[str] = None
    unresolved_alerts: list[ContextAlert] = Field(default_factory=list)
    provider: str
    is_mock: bool


class FeedbackCreate(BaseModel):
    suggestion_id: str
    suggestion_text: str
    verdict: Literal["used", "edited", "rejected"]
    note: str = ""


# --- Health -------------------------------------------------------------------


class HealthResponse(BaseModel):
    status: str
    provider: str
    is_mock: bool
    model: Optional[str] = None
    notes: list[str] = Field(default_factory=list)
    warnings: list[str] = Field(
        default_factory=list,
        description="Configuration problems the user needs to fix, shown prominently.",
    )
