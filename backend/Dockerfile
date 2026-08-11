FROM python:3.12.8-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

RUN groupadd --gid 10001 helmet \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin helmet \
    && install -d -o helmet -g helmet -m 0700 /var/lib/helmet

WORKDIR /app
COPY backend/requirements-production.txt /tmp/requirements-production.txt
RUN python3 -m pip install --no-compile --requirement /tmp/requirements-production.txt \
    && rm /tmp/requirements-production.txt

COPY --chown=helmet:helmet backend /app/backend
COPY --chown=helmet:helmet dashboard /app/dashboard
COPY --chown=helmet:helmet --chmod=0555 deploy/backend-stack/backend-entrypoint.sh /usr/local/bin/helmet-backend

USER 10001:10001
EXPOSE 18080
ENTRYPOINT ["/usr/local/bin/helmet-backend"]
