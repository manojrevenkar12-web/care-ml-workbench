# syntax=docker/dockerfile:1.7
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
ARG VITE_OIDC_ISSUER
ARG VITE_OIDC_CLIENT_ID
RUN npm run build

# Static files behind an unprivileged nginx; /api is proxied to the API service.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY security-headers.inc.template /etc/nginx/templates/security-headers.inc.template
COPY --from=build /src/dist /usr/share/nginx/html
ENV API_UPSTREAM=http://api:8080 CSP_CONNECT_EXTRA=""
EXPOSE 8080
