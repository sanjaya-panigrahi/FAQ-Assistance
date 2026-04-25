# Build the UI inside Docker so dist is always refreshed during image build.
FROM node:18-slim AS build
WORKDIR /app

# Install build tools for optional dependencies (e.g., rollup native module compilation)
RUN apt-get update && apt-get install -y --no-install-recommends \
	build-essential \
	python3 \
	&& rm -rf /var/lib/apt/lists/*

COPY package.json ./
RUN npm install --package-lock=false

COPY . .

# Optional build-time overrides.
ARG VITE_API_MODE=direct
ARG VITE_API_BASE_URL=http://localhost:9080
ARG VITE_INGESTION_API_URL=http://localhost:9020/api/faq-ingestion
ENV VITE_API_MODE=${VITE_API_MODE}
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_INGESTION_API_URL=${VITE_INGESTION_API_URL}

RUN npm run build
RUN test -f /app/dist/index.html

# Runtime image: serve built assets with nginx.
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# SPA routing config (try_files -> index.html, static-asset caching)
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 5173
CMD ["nginx", "-g", "daemon off;"]