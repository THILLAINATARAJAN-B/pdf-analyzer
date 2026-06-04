# Stage 1
FROM node:20-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm ci --legacy-peer-deps

COPY . .

ARG API_BASE_URL=http://localhost:8080/api/v1
RUN sed -i "s|%%API_BASE_URL%%|${API_BASE_URL}|g" src/environments/environment.prod.ts

RUN npm run build

# Stage 2
FROM nginx:1.27-alpine

RUN rm /etc/nginx/conf.d/default.conf

COPY nginx.conf /etc/nginx/templates/default.conf.template

COPY --from=builder /app/dist/pdf-analyzer-frontend /usr/share/nginx/html

EXPOSE 80

CMD ["/bin/sh", "-c", "envsubst '$PORT' < /etc/nginx/templates/default.conf.template > /etc/nginx/conf.d/default.conf && nginx -g 'daemon off;'"]