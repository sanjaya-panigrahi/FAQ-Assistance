FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install

FROM node:20-alpine AS build
WORKDIR /app
ARG VITE_API_BASE_URL=http://localhost:9080
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
COPY --from=deps /app/node_modules /app/node_modules
COPY . .
RUN npm run build

# Final stage: nginx:alpine is ~8 MB vs ~175 MB for node:20-alpine
# Using nginx to serve the pre-built Vite bundle is far more memory/CPU efficient
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# SPA routing config (try_files -> index.html, static-asset caching)
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 5173
CMD ["nginx", "-g", "daemon off;"]