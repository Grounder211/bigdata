FROM node:20-alpine
WORKDIR /app
COPY package.json .
RUN npm install --only=prod
COPY server.js .
ENV DBHOST=dbstorage
ENV PORT=3001
ENV STATS_SUMMARY=/app/logs/summary.json
CMD ["node", "server.js"]

