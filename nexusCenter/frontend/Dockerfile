FROM node:22-alpine

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm config set registry https://registry.npmmirror.com \
    && npm ci --ignore-scripts
COPY scripts ./scripts
COPY dist ./dist
COPY next.config.ts vite.config.ts tsconfig.json ./
ENV NODE_ENV=production PORT=3000
EXPOSE 3000
CMD ["node", "scripts/start.mjs", "--host", "0.0.0.0", "--port", "3000"]
