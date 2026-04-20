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

FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/dist /app/dist
RUN npm install -g serve
EXPOSE 5173
ENTRYPOINT ["serve", "-s", "dist", "-l", "5173"]