FROM python:3.12-slim

WORKDIR /code

# Dependencies first: this layer is cached until requirements.txt itself changes,
# so editing application code does not reinstall the world on every rebuild.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY alembic.ini .
COPY alembic/ ./alembic/
COPY app/ ./app/

EXPOSE 8000

# Migrations run at container start rather than in the image build: the database is a
# separate se1rvice that does not exist at build time. The entrypoint waits for it via
# compose's healthcheck, so `upgrade head` always meets a reachable server.
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000"]
