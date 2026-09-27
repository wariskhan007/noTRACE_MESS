# NoTrace signaling server — Docker image for Render.com
#
# This packages ONLY the signaling-server/ folder. It does NOT (and
# cannot) package the Android app itself — an APK is not a server
# process, so it has nothing to run inside a container. This image is
# the piece the Android app connects to over wss:// for WebRTC
# signaling and offline message relay.

FROM node:20-alpine

WORKDIR /app

# Install dependencies first so this layer is cached unless
# package.json/package-lock.json change.
COPY package*.json ./
RUN npm install --omit=dev

# Now copy the actual server code.
COPY . .

# Render sets $PORT itself and routes external HTTPS/WSS traffic to
# it. 8080 here is just the local fallback default from index.js.
EXPOSE 8080

# Run as the non-root "node" user the base image already provides.
USER node

CMD ["node", "index.js"]
