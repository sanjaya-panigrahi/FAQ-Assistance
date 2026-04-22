# Runtime-only image: serve prebuilt Vite dist via nginx.
FROM nginx:1.27-alpine
COPY dist /usr/share/nginx/html
RUN test -f /usr/share/nginx/html/index.html
# SPA routing config (try_files -> index.html, static-asset caching)
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 5173
CMD ["nginx", "-g", "daemon off;"]