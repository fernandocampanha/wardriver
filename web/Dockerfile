FROM node:22-slim AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM python:3.12-slim
RUN useradd --system --uid 10001 --no-create-home wardriver
WORKDIR /srv
COPY backend/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY backend/app ./app
COPY --from=web /web/dist ./frontend_dist
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
USER wardriver
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=4s --start-period=40s \
  CMD python -c "import urllib.request as u; u.urlopen('http://127.0.0.1:8080/api/health', timeout=3)"
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080", \
     "--no-proxy-headers", "--no-server-header", "--timeout-keep-alive", "10"]
