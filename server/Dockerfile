# syntax=docker/dockerfile:1

FROM node:22-trixie-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY scripts ./scripts
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-trixie-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      chromium \
      fonts-noto-core fonts-noto-cjk fonts-noto-color-emoji fonts-liberation \
      openssl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

ENV NODE_ENV=production \
    DATA_DIR=/data \
    CHROME_PATH=/usr/bin/chromium

WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
COPY debug ./debug
RUN mkdir -p /data && chown node:node /data

# Chromium runs as this unprivileged user with its namespace sandbox enabled
# (requires the seccomp profile from docker-compose.yml).
USER node
VOLUME /data
EXPOSE 8686 8687

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD node -e "require('https').get({host:'127.0.0.1',port:process.env.PORT||8686,path:'/health',rejectUnauthorized:false},r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"

CMD ["node", "dist/main.js"]
