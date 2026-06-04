# Stage 1 — Build Angular app
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci --legacy-peer-deps
COPY . .

# API_BASE_URL is injected at Railway build time
ARG API_BASE_URL=http://localhost:8080/api/v1
RUN sed -i "s|%%API_BASE_URL%%|${API_BASE_URL}|g" src/environments/environment.prod.ts
RUN npm run build -- --configuration production

# Stage 2 — Serve with nginx
FROM nginx:1.27-alpine AS runtime
RUN rm /etc/nginx/conf.d/default.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist/pdf-analyzer-frontend/browser /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]