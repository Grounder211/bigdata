FROM node:18-alpine
RUN npm install -g nodemon
EXPOSE 3000
WORKDIR /app
COPY . .
RUN npm install

# Environment variables for Qualitas Corpus processing
ENV DEBUG='csconsumer:*'
ENV PORT=3000
ENV CHUNKSIZE=5
ENV MAX_FILE_SIZE=10485760
ENV MAX_TOKENS=100000
ENV BATCH_SIZE=100
ENV URL='http://localhost:3000/'

# Create data directory for statistics and index
RUN mkdir -p /app/data

ENTRYPOINT ["npm", "run", "dev"]
