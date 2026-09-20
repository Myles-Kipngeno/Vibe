# The backend as a long-running process, for a host that can keep one alive
# (Fly, Render, Railway, a VPS).
#
# Deliberately not a serverless function. LocalStore writes a JSON file, and a
# serverless filesystem is per-invocation and not shared, so local mode would
# appear to work and lose every write. A deployed backend should be running
# with accounts on anyway -- app/main.py refuses to start otherwise once
# CORS_ORIGINS names a public origin.

FROM python:3.14-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

# Dependencies first, so a code change does not reinstall them.
COPY requirements.txt ./
RUN python -m pip install --no-cache-dir --upgrade pip \
    && python -m pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Not root: nothing here needs it.
RUN useradd --create-home --uid 10001 vibe && chown -R vibe:vibe /app
USER vibe

# Most hosts inject PORT. 8000 is the fallback for `docker run` locally.
ENV PORT=8000
EXPOSE 8000

# Shell form so $PORT expands.
CMD python -m uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}
