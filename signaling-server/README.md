# NoTrace signaling server

A small WebSocket relay that helps two NoTrace devices find each other
and exchange the handshake information WebRTC needs to connect
directly (SDP offers/answers, ICE candidates), plus Signal Protocol key
bundles so sessions can establish automatically when both people are
online. As of Phase 6, it also temporarily holds actual message
ciphertext for a recipient who's offline right now (up to 7 days, or
until they come back online and it's delivered) — a real offline
delivery path, not just live routing. See the comment block at the top
of `index.js` for exactly what it does and doesn't do — short version:
it never sees message content in a readable form (only opaque
ciphertext it can't decrypt), stores nothing to disk, and everything
held for an offline recipient expires or gets deleted once delivered.

## Deploying to Render.com (recommended — free tier, automatic TLS)

This gives you a `wss://your-app.onrender.com` URL, which is what the
Android app needs (browsers/Android both require secure WebSockets —
`wss://`, not `ws://` — for anything other than local testing).

1. Push this `signaling-server/` folder to its own GitHub repo (or a
   subfolder of the main NoTrace repo — Render lets you set a "root
   directory").
2. On [render.com](https://render.com), create a new **Web Service**,
   connect your repo.
3. Settings:
   - **Root Directory:** `signaling-server` (if it's a subfolder)
   - **Runtime:** Node
   - **Build Command:** `npm install`
   - **Start Command:** `npm start`
   - **Instance Type:** Free is fine for testing.
4. Deploy. Once live, your signaling URL is
   `wss://<your-service-name>.onrender.com`.
5. Put that URL into the Android app's signaling server setting (see
   the main project's `HOW_TO_BUILD_AND_TEST.md` for exactly where).

**Free-tier note:** Render's free web services spin down after
inactivity and take a few seconds to wake back up on the next
connection — expect the first connection after a while to be slow.
Fine for testing; worth knowing about before assuming something's
broken.

## Running locally (for testing against an emulator)

```
cd signaling-server
npm install
npm start
```

This starts a plain `ws://localhost:8080` server. An Android emulator
reaches your host machine at `10.0.2.2`, so point the app at
`ws://10.0.2.2:8080` for local testing — the app's network security
config allows cleartext (non-TLS) traffic to that one address only,
never anywhere else (see the main project's Phase 5 notes).

## Environment variables

- `PORT` — defaults to `8080`. Render sets this automatically.

## Logs

Render's dashboard shows `console.log`/`console.error` output live.
Nothing sensitive is ever logged — no message content, no keys, no
signatures — only connection lifecycle and error *reasons* (short
strings like `"identity-mismatch"`), matching the plan's "never log
plaintext" and "minimize logging" rules.
