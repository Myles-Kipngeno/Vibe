"""A ceiling on how often one person can spend money.

`/suggest` is the only endpoint that calls a paid model. Everything else costs
CPU; this one costs cents per request, against a key the user does not own.
Without a limit, anyone with an account can loop it, and the first anybody
would know is the bill.

Deliberately in-process and dependency-free. It counts requests per identity in
a sliding window, which is enough for the one instance this runs on. It is not
enough for several: each would keep its own count, and the effective limit
would multiply. A deployment that scales past one instance needs a shared
counter (Redis, or the database), and this module is the thing to replace --
which is why the limit and the window are configuration, not constants buried
in a decorator.
"""

from __future__ import annotations

import threading
import time
from collections import defaultdict, deque

from fastapi import Header, HTTPException, Request

from ..config import get_settings
from ..storage.supabase_store import subject_from_token


class SlidingWindow:
    """Counts events per key over a fixed window, forgetting what fell out."""

    def __init__(self) -> None:
        self._hits: dict[str, deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, key: str, limit: int, window_seconds: float) -> float | None:
        """Records a hit. Returns seconds to wait when over the limit.

        Returning the wait rather than a bare boolean is what lets the response
        carry Retry-After, so a client can behave instead of hammering.
        """
        now = time.monotonic()
        cutoff = now - window_seconds
        with self._lock:
            hits = self._hits[key]
            while hits and hits[0] <= cutoff:
                hits.popleft()
            if len(hits) >= limit:
                return max(0.0, hits[0] + window_seconds - now)
            hits.append(now)
            return None

    def reset(self) -> None:
        """Used by tests. Nothing in the app needs it."""
        with self._lock:
            self._hits.clear()


_windows = SlidingWindow()


def reset_limits() -> None:
    _windows.reset()


def _identity(request: Request, authorization: str | None) -> str:
    """Who is being limited.

    The signed-in user where there is one, so a limit follows the account
    rather than the network it is used from. Otherwise the client address,
    which is all local mode has -- and local mode is one person anyway.
    """
    if authorization:
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() == "bearer" and token.strip():
            subject = subject_from_token(token.strip())
            if subject:
                return f"user:{subject}"
            # An unreadable token still gets limited, rather than sharing the
            # anonymous bucket with everyone else behind the same proxy.
            return f"token:{hash(token.strip())}"
    client = request.client
    return f"ip:{client.host if client else 'unknown'}"


def limit_generation(
    request: Request, authorization: str | None = Header(default=None)
) -> None:
    """FastAPI dependency for the endpoint that calls a paid model."""
    settings = get_settings()
    if settings.suggest_per_hour <= 0 and settings.suggest_per_minute <= 0:
        return

    key = _identity(request, authorization)

    for limit, window, label in (
        (settings.suggest_per_minute, 60.0, "a minute"),
        (settings.suggest_per_hour, 3600.0, "an hour"),
    ):
        if limit <= 0:
            continue
        wait = _windows.check(f"{key}:{window}", limit, window)
        if wait is not None:
            raise HTTPException(
                status_code=429,
                detail=(
                    f"That is {limit} replies in {label}, which is as fast as this "
                    "is meant to go. Try again in "
                    f"{max(1, round(wait))} seconds."
                ),
                headers={"Retry-After": str(max(1, round(wait)))},
            )
