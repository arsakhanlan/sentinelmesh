# Playwright image already has Chromium + system deps installed.
FROM mcr.microsoft.com/playwright/python:v1.48.0-jammy

WORKDIR /app

# Install deps first for build cache friendliness
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Project
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir -e .

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8090/health').status==200 else 1)"

CMD ["uvicorn", "sentinelmesh_agents.main:app", "--host", "0.0.0.0", "--port", "8090"]
