"""FastAPI application entry point.

Run it with:  uvicorn app.main:app --reload --port 8000
"""

from __future__ import annotations

import logging

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api import conversation, profiles
from .api.deps import get_provider
from .config import get_settings
from .schemas import HealthResponse

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("vibe")

settings = get_settings()

@asynccontextmanager
async def lifespan(_: FastAPI):
    provider = get_provider()
    log.info(
        "Vibe API ready. provider=%s model=%s mock=%s data_dir=%s",
        provider.name,
        provider.model,
        provider.is_mock,
        settings.data_dir,
    )
    if not settings.log_message_content:
        log.info("Message content logging is OFF (LOG_MESSAGE_CONTENT=false).")
    yield


app = FastAPI(
    lifespan=lifespan,
    title="Vibe -- conversation assistant API",
    version="0.1.0",
    description=(
        "Backend for a private conversation assistant. It analyses a pasted "
        "conversation, flags what it cannot know, and drafts replies in the "
        "user's own voice."
    ),
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=list(settings.cors_origins),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(conversation.router)
app.include_router(profiles.style_router)
app.include_router(profiles.contacts_router)
app.include_router(profiles.data_router)


@app.get("/api/health", response_model=HealthResponse)
def health() -> HealthResponse:
    provider = get_provider()
    notes: list[str] = []
    if provider.is_mock:
        notes.append(
            "Running in offline mode. Suggestions are fixed templates, not model "
            "output. Set ANTHROPIC_API_KEY in backend/.env to enable real generation."
        )
    notes.append(
        "Conversation analysis and context alerts are computed locally and do not "
        "leave this machine."
    )
    if not provider.is_mock:
        notes.append(
            "Reply generation sends the conversation text to the configured AI "
            "provider."
        )
    warnings: list[str] = []
    key = settings.anthropic_api_key
    if provider.name == "anthropic" and key and not key.startswith("sk-ant-"):
        # Caught here rather than on the user's first request: an Anthropic key
        # always starts with sk-ant-, so anything else is a misconfiguration and
        # every generation call would fail with a 401.
        warnings.append(
            "ANTHROPIC_API_KEY does not look like an Anthropic key (they start with "
            "'sk-ant-'). Reply generation will fail with a 401. Set a valid key in "
            "backend/.env, or set AI_PROVIDER=mock to use offline templates."
        )

    return HealthResponse(
        status="ok",
        provider=provider.name,
        is_mock=provider.is_mock,
        model=provider.model,
        notes=notes,
        warnings=warnings,
    )
