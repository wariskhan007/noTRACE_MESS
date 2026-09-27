'use strict';

/**
 * NoTrace Messenger — authenticated signaling relay + temporary
 * ciphertext relay (Phase 5 + Phase 6).
 *
 * What this server does, precisely (Phase 0 threat model / data
 * inventory; Phase 5 "authenticated signaling... direct P2P
 * preference... TURN fallback"; Phase 6 "temporary ciphertext relay,
 * with expiry, acknowledgments, duplicate prevention, deletion, rate
 * limits, and metadata minimization"):
 *   - Routes SDP offers/answers, ICE candidates, and Signal Protocol
 *     key bundles between exactly two currently-connected clients.
 *   - For actual message ciphertext specifically (payload.kind ===
 *     "relayed-message"): if the recipient isn't currently connected,
 *     HOLDS the opaque ciphertext in memory for up to 7 days or until
 *     the recipient acknowledges receiving it, whichever comes first,
 *     then delivers it automatically the next time that recipient
 *     authenticates. Nothing else (handshake/SDP/ICE/bundle payloads)
 *     is ever queued - those are only meaningful between two peers who
 *     are online at the same moment, so queuing them would be useless.
 *   - Requires the same signature-based handshake as Phase 5 before
 *     routing or queuing anything for a given randomId.
 *
 * What this server explicitly does NOT do:
 *   - Persist anything to disk. All state - connections, the identity
 *     TOFU bindings, and the relay queue - is in-memory only, gone on
 *     restart. This is a real, disclosed limitation: a server restart
 *     loses any not-yet-delivered queued messages. Accepted per the
 *     Phase 0 "temporary infrastructure, short retention" decision -
 *     this was never meant to be a durable message store.
 *   - See, store, or need any decryption key. Queued items are the
 *     exact same opaque ciphertext blob a live "signal" would have
 *     carried - this server cannot read them either way.
 *   - Guarantee delivery. This is explicitly a *temporary* relay, not
 *     a durable queue - Section 6 lets a shorter/looser guarantee here
 *     because Phase 4's Double Ratchet + Phase 4's local encrypted
 *     message log are the actual source of truth on each device.
 *
 * --- Authentication handshake (unchanged from Phase 5) ---
 *   1. Client -> server: { type: "hello", randomId, identityPublicKey (base64) }
 *   2. Server TOFU-binds randomId -> public key (rejects a later
 *      different key for the same randomId).
 *   3. Server -> client: { type: "challenge", nonce (base64) }
 *   4. Client signs the nonce with its identity private key
 *      (Curve.calculateSignature - same primitive as signed-prekey
 *      signing, no new cryptography): { type: "auth", signature }
 *   5. Server verifies; on success { type: "ready" }, and immediately
 *      flushes any queued messages for this randomId (see below).
 *
 * --- Message routing ---
 *   Client -> server: { type: "signal", to: randomId, payload: {...} }
 *   Server -> recipient (if online): { type: "signal", from, payload }
 *   Server -> sender (if recipient offline, payload.kind !== "relayed-message"):
 *     { type: "peer-offline", to }
 *   Server -> sender (if recipient offline, payload.kind === "relayed-message"):
 *     { type: "queued", to, id } — confirms it was HELD, not dropped.
 *
 * --- Relay queue (Phase 6) ---
 *   On auth success: server sends { type: "queued-item", id, from, payload }
 *   for every held item addressed to this randomId, oldest first. The
 *   item stays in the queue (in case the client disconnects again
 *   before acking) until:
 *     Client -> server: { type: "ack", id }  — server deletes it, OR
 *     it exceeds EXPIRY_MS (7 days) — a periodic sweep deletes it, OR
 *   Duplicate prevention: the same item may be re-flushed on a
 *   reconnect that happens before an ack arrives (this is a "temporary
 *   relay", not exactly-once delivery) - the client's own Signal
 *   Protocol replay protection (Phase 4's DuplicateMessageException
 *   handling) is the real backstop against acting on the same
 *   ciphertext twice, exactly as it already is for any redelivery.
 *   Rate limiting: MAX_QUEUE_PER_RECIPIENT caps how many messages can
 *   be held for one offline recipient (protects server memory from a
 *   single sender flooding one inbox); MAX_PAYLOAD_BYTES caps a single
 *   relayed message's size.
 *   Metadata minimization: a queued item stores only what routing
 *   requires - id, from, the opaque payload, and a queued timestamp -
 *   nothing about it is ever logged.
 *
 * HIGHEST-RISK-OF-DRIFT NOTE: unchanged from Phase 5 - the exact
 * @signalapp/libsignal-client Node API could not be verified against a
 * real `npm install` + compile in this sandbox (no network access
 * here - Render's build step DOES have network access).
 */

const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const { PublicKey } = require('@signalapp/libsignal-client');

const PORT = process.env.PORT || 8080;
const NONCE_TTL_MS = 30_000;
const RELAY_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000; // 7 days, per Phase 0's locked decision
const MAX_QUEUE_PER_RECIPIENT = 200; // rate limit: cap held messages per offline recipient
const MAX_PAYLOAD_BYTES = 64 * 1024; // rate limit: cap a single relayed message's size
const SWEEP_INTERVAL_MS = 60 * 60 * 1000; // hourly expiry sweep

/**
 * Phase 13 review addition: per-IP abuse limits.
 *
 * Nothing below here existed before Phase 13 — a prior review pass
 * found the server had per-recipient queue/payload caps (above) but
 * nothing bounding how many connections or messages a single IP could
 * throw at the server, which made it possible to flood the process
 * with sockets or churn through many randomId registrations to grow
 * identityBindings/relayQueues without limit. These are deliberately
 * simple in-memory counters (no new dependency) consistent with the
 * rest of this file's "small, in-memory, no persistence" design.
 */
const MAX_CONNECTIONS_PER_IP = 8; // concurrent open sockets
const MAX_MESSAGES_PER_WINDOW_PER_IP = 120; // any message type, per IP_RATE_WINDOW_MS
const IP_RATE_WINDOW_MS = 60_000;
const MAX_TOTAL_IDENTITY_BINDINGS = 50_000; // hard ceiling on distinct randomIds ever seen

/** client IP -> number of currently-open sockets from that IP. */
const connectionsByIp = new Map();

/** client IP -> { count, windowStartedAt } — reset once IP_RATE_WINDOW_MS elapses. */
const messageRateByIp = new Map();

/** randomId -> { publicKeyBytes: Buffer } — persists across reconnects, for TOFU. Memory-only. */
const identityBindings = new Map();

/** randomId -> { ws, authenticated: boolean } — cleared on disconnect. */
const activeConnections = new Map();

/** ws -> { randomId, pendingNonce: Buffer, nonceExpiresAt: number } — handshake-in-progress state. */
const handshakeState = new Map();

/** ws -> client IP, recorded at connection time so 'close' can decrement connectionsByIp correctly. */
const ipByConnection = new Map();

/**
 * Best-effort client IP extraction. When deployed behind Render's (or
 * any PaaS) edge proxy, the raw TCP socket address is the proxy's own
 * address, not the client's — the platform's edge sets/overwrites
 * X-Forwarded-For with the real client IP itself, so a directly-
 * connecting attacker can't spoof it there. If this server is ever run
 * with a raw public socket (no proxy in front), remove the header
 * check below so a client can't fake its own IP to dodge the limits.
 */
function getClientIp(req) {
    const forwarded = req.headers['x-forwarded-for'];
    if (typeof forwarded === 'string' && forwarded.length > 0) {
        return forwarded.split(',')[0].trim();
    }
    return req.socket.remoteAddress || 'unknown';
}

function isMessageRateLimited(ip) {
    const now = Date.now();
    const entry = messageRateByIp.get(ip);
    if (!entry || now - entry.windowStartedAt > IP_RATE_WINDOW_MS) {
        messageRateByIp.set(ip, { count: 1, windowStartedAt: now });
        return false;
    }
    entry.count += 1;
    return entry.count > MAX_MESSAGES_PER_WINDOW_PER_IP;
}

/**
 * randomId -> Array<{ id, from, payload, queuedAtEpochMillis, expiresAtEpochMillis }>
 * Only ever holds payload.kind === "relayed-message" items (Phase 6).
 * Memory-only - gone on server restart, per the Phase 0 "temporary
 * infrastructure" decision.
 */
const relayQueues = new Map();

const wss = new WebSocketServer({ port: PORT });

console.log(`NoTrace signaling server listening on :${PORT}`);

wss.on('connection', (ws, req) => {
    const ip = getClientIp(req);
    const currentForIp = connectionsByIp.get(ip) ?? 0;
    if (currentForIp >= MAX_CONNECTIONS_PER_IP) {
        // Over the concurrent-connection cap for this IP: refuse before
        // doing any other work (no handshake state allocated for it).
        send(ws, { type: 'error', reason: 'too-many-connections' });
        ws.close();
        return;
    }
    connectionsByIp.set(ip, currentForIp + 1);
    ipByConnection.set(ws, ip);

    ws.on('message', (raw) => {
        if (isMessageRateLimited(ip)) {
            send(ws, { type: 'error', reason: 'rate-limited' });
            return;
        }

        let msg;
        try {
            msg = JSON.parse(raw.toString('utf8'));
        } catch (e) {
            return send(ws, { type: 'error', reason: 'invalid-json' });
        }

        try {
            switch (msg.type) {
                case 'hello':
                    return handleHello(ws, msg);
                case 'auth':
                    return handleAuth(ws, msg);
                case 'signal':
                    return handleSignal(ws, msg);
                case 'ack':
                    return handleAck(ws, msg);
                default:
                    return send(ws, { type: 'error', reason: 'unknown-message-type' });
            }
        } catch (e) {
            console.error('Error handling message:', e);
            send(ws, { type: 'error', reason: 'server-error' });
        }
    });

    ws.on('close', () => {
        const state = handshakeState.get(ws);
        handshakeState.delete(ws);
        const randomId = findConnectedRandomId(ws);
        if (randomId) {
            activeConnections.delete(randomId);
        }

        const connIp = ipByConnection.get(ws);
        ipByConnection.delete(ws);
        if (connIp) {
            const remaining = (connectionsByIp.get(connIp) ?? 1) - 1;
            if (remaining <= 0) {
                connectionsByIp.delete(connIp);
            } else {
                connectionsByIp.set(connIp, remaining);
            }
        }
    });
});

function handleHello(ws, msg) {
    const { randomId, identityPublicKey } = msg;
    if (!isValidRandomId(randomId) || typeof identityPublicKey !== 'string') {
        return send(ws, { type: 'error', reason: 'invalid-hello' });
    }

    let publicKeyBytes;
    try {
        publicKeyBytes = Buffer.from(identityPublicKey, 'base64');
        // Validate it actually parses as a Curve25519 public key before trusting it.
        PublicKey.deserialize(publicKeyBytes);
    } catch (e) {
        return send(ws, { type: 'error', reason: 'invalid-public-key' });
    }

    const existingBinding = identityBindings.get(randomId);
    if (existingBinding && !existingBinding.publicKeyBytes.equals(publicKeyBytes)) {
        // Someone else already proved ownership of this randomId with a
        // DIFFERENT key. Refuse rather than silently rebind - this is
        // exactly the impersonation case TOFU is meant to catch.
        return send(ws, { type: 'error', reason: 'identity-mismatch' });
    }
    if (!existingBinding) {
        if (identityBindings.size >= MAX_TOTAL_IDENTITY_BINDINGS) {
            // Hard ceiling on distinct randomIds this server will ever
            // bind, so registering many new identities can't grow
            // process memory without bound. A real deployment expecting
            // to outgrow this should raise the constant, not remove it.
            return send(ws, { type: 'error', reason: 'server-at-capacity' });
        }
        identityBindings.set(randomId, { publicKeyBytes });
    }

    const nonce = crypto.randomBytes(32);
    handshakeState.set(ws, {
        randomId,
        publicKeyBytes,
        pendingNonce: nonce,
        nonceExpiresAt: Date.now() + NONCE_TTL_MS
    });

    send(ws, { type: 'challenge', nonce: nonce.toString('base64') });
}

function handleAuth(ws, msg) {
    const state = handshakeState.get(ws);
    if (!state) {
        return send(ws, { type: 'error', reason: 'no-handshake-in-progress' });
    }
    if (Date.now() > state.nonceExpiresAt) {
        handshakeState.delete(ws);
        return send(ws, { type: 'error', reason: 'challenge-expired' });
    }

    const { signature } = msg;
    if (typeof signature !== 'string') {
        return send(ws, { type: 'error', reason: 'invalid-auth' });
    }

    let verified = false;
    try {
        const publicKey = PublicKey.deserialize(state.publicKeyBytes);
        const signatureBytes = Buffer.from(signature, 'base64');
        verified = publicKey.verify(state.pendingNonce, signatureBytes);
    } catch (e) {
        console.error('Signature verification error:', e);
        verified = false;
    }

    if (!verified) {
        send(ws, { type: 'error', reason: 'signature-invalid' });
        ws.close();
        return;
    }

    // Success. This socket becomes the active route for this randomId,
    // replacing any previous connection (e.g. app was killed and
    // reopened without a clean close).
    const previous = activeConnections.get(state.randomId);
    if (previous && previous.ws !== ws) {
        try { previous.ws.close(); } catch (e) { /* already gone */ }
    }
    activeConnections.set(state.randomId, { ws });
    handshakeState.delete(ws);

    send(ws, { type: 'ready' });
    flushQueueTo(state.randomId, ws);
}

/**
 * Sends every currently-held, non-expired queued item for this
 * randomId over the given (freshly authenticated) socket. Items are
 * NOT removed here - only an explicit ack removes them - so a client
 * that disconnects again before acking will simply be re-sent the same
 * items on its next successful auth (Phase 6: "temporary relay, not
 * exactly-once delivery" - the client's own replay protection is the
 * real backstop, as documented at the top of this file).
 */
function flushQueueTo(randomId, ws) {
    const queue = relayQueues.get(randomId);
    if (!queue || queue.length === 0) return;

    const now = Date.now();
    for (const item of queue) {
        if (item.expiresAtEpochMillis < now) continue; // sweep will clean these up
        send(ws, { type: 'queued-item', id: item.id, from: item.from, payload: item.payload });
    }
}

function handleSignal(ws, msg) {
    const randomId = findConnectedRandomId(ws);
    if (!randomId) {
        return send(ws, { type: 'error', reason: 'not-authenticated' });
    }

    const { to, payload } = msg;
    if (!isValidRandomId(to) || payload === undefined) {
        return send(ws, { type: 'error', reason: 'invalid-signal' });
    }

    const payloadSize = Buffer.byteLength(JSON.stringify(payload), 'utf8');
    if (payloadSize > MAX_PAYLOAD_BYTES) {
        return send(ws, { type: 'error', reason: 'payload-too-large' });
    }

    const target = activeConnections.get(to);
    if (target) {
        return send(target.ws, { type: 'signal', from: randomId, payload });
    }

    // Recipient not connected. Only actual chat-message ciphertext gets
    // queued (Phase 6) - a stale SDP offer/answer/ICE candidate or key
    // bundle would be useless by the time the recipient reconnects, so
    // those still just tell the sender the peer is offline right now.
    if (payload && payload.kind === 'relayed-message') {
        const queue = relayQueues.get(to) ?? [];
        if (queue.length >= MAX_QUEUE_PER_RECIPIENT) {
            return send(ws, { type: 'error', reason: 'recipient-queue-full' });
        }
        const item = {
            id: crypto.randomBytes(16).toString('hex'),
            from: randomId,
            payload,
            queuedAtEpochMillis: Date.now(),
            expiresAtEpochMillis: Date.now() + RELAY_EXPIRY_MS
        };
        queue.push(item);
        relayQueues.set(to, queue);
        return send(ws, { type: 'queued', to, id: item.id });
    }

    return send(ws, { type: 'peer-offline', to });
}

function handleAck(ws, msg) {
    const randomId = findConnectedRandomId(ws);
    if (!randomId) {
        return send(ws, { type: 'error', reason: 'not-authenticated' });
    }
    const { id } = msg;
    const queue = relayQueues.get(randomId);
    if (!queue) return;
    const filtered = queue.filter((item) => item.id !== id);
    if (filtered.length === 0) {
        relayQueues.delete(randomId);
    } else {
        relayQueues.set(randomId, filtered);
    }
}

function findConnectedRandomId(ws) {
    for (const [randomId, entry] of activeConnections.entries()) {
        if (entry.ws === ws) return randomId;
    }
    return null;
}

function isValidRandomId(id) {
    return typeof id === 'string' && /^[0-9]{12}$/.test(id);
}

function send(ws, obj) {
    if (ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify(obj));
    }
}

// Periodic sweep of expired per-IP message-rate windows (Phase 13
// addition) - without this, messageRateByIp would keep one entry per
// distinct IP ever seen for the life of the process.
setInterval(() => {
    const now = Date.now();
    for (const [ip, entry] of messageRateByIp.entries()) {
        if (now - entry.windowStartedAt > IP_RATE_WINDOW_MS) {
            messageRateByIp.delete(ip);
        }
    }
}, IP_RATE_WINDOW_MS);

// Periodic sweep of stale in-progress handshakes (never got an "auth" reply).
setInterval(() => {
    const now = Date.now();
    for (const [ws, state] of handshakeState.entries()) {
        if (now > state.nonceExpiresAt) {
            handshakeState.delete(ws);
        }
    }
}, NONCE_TTL_MS);

/**
 * Periodic sweep deleting expired relay-queue items (Phase 6:
 * "expiry... deletion"). A message held for a recipient who never
 * comes back within RELAY_EXPIRY_MS is simply gone - matching the
 * Phase 0 privacy claim that this is temporary infrastructure, not a
 * durable message store.
 */
setInterval(() => {
    const now = Date.now();
    for (const [randomId, queue] of relayQueues.entries()) {
        const remaining = queue.filter((item) => item.expiresAtEpochMillis >= now);
        if (remaining.length === 0) {
            relayQueues.delete(randomId);
        } else if (remaining.length !== queue.length) {
            relayQueues.set(randomId, remaining);
        }
    }
}, SWEEP_INTERVAL_MS);
