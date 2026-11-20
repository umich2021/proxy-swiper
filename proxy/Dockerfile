# Always-on brain server: npm run local with the Claude subscription backend. See README.md.
FROM node:24-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*
RUN npm install -g @anthropic-ai/claude-code@2.1.314
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Mount points for the ledger and the Claude login, owned by the unprivileged user.
RUN mkdir -p /app/data /home/node/.claude && chown -R node:node /app/data /home/node/.claude
ENV CLAUDE_CONFIG_DIR=/home/node/.claude DISABLE_AUTOUPDATER=1
USER node
EXPOSE 8787
CMD ["node_modules/.bin/tsx", "src/local.ts"]
