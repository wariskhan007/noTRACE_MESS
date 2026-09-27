'use strict';

/**
 * NoTrace Messenger — authenticated signaling relay + temporary
 * ciphertext relay (Phase 5 + Phase 6).
 */

const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const http = require('http');
const { PublicKey } = require('@signalapp/libsignal-client');

const PORT = process.env.PORT || 8080;
const NONCE_TTL_MS = 30_000;
const RELAY_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_QUEUE_PER_RECIPIENT = 200;
const MAX_PAYLOAD_BYTES = 64 * 1024;
const SWEEP_INTERVAL_MS = 60 * 60 * 1000;

const MAX_CONNECTIONS_PER_IP = 8;
const MAX_MESSAGES_PER_WINDOW_PER_IP = 120;
const IP_RATE_WINDOW_MS = 60_000;
const MAX_TOTAL_IDENTITY_BINDINGS = 50_000;

const connectionsByIp = new Map();
const messageRateByIp = new Map();
const identityBindings = new Map();
const activeConnections = new Map();
const handshakeState = new Map();
const ipByConnection = new Map();
const relayQueues = new Map();

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
        messageRateByIp.set(ip, {
            count: 1,
            windowStartedAt: now
        });

        return false;
    }

    entry.count += 1;
    return entry.count > MAX_MESSAGES_PER_WINDOW_PER_IP;
}

/**
 * Render-compatible HTTP server.
 *
 * Render exposes the application publicly and handles TLS termination.
 * The Android client connects using:
 *
 * wss://notrace-mess.onrender.com
 */
const server = http.createServer((req, res) => {
    res.writeHead(200, {
        'Content-Type': 'text/plain'
    });

    res.end('NoTrace signaling server is running.\n');
});

const wss = new WebSocketServer({ server });

server.listen(PORT, '0.0.0.0', () => {
    console.log(`NoTrace signaling server listening on :${PORT}`);
});

wss.on('connection', (ws, req) => {
    const ip = getClientIp(req);
    const currentForIp = connectionsByIp.get(ip) ?? 0;

    if (currentForIp >= MAX_CONNECTIONS_PER_IP) {
        send(ws, {
            type: 'error',
            reason: 'too-many-connections'
        });

        ws.close();
        return;
    }

    connectionsByIp.set(ip, currentForIp + 1);
    ipByConnection.set(ws, ip);

    ws.on('message', (raw) => {
        if (isMessageRateLimited(ip)) {
            send(ws, {
                type: 'error',
                reason: 'rate-limited'
            });

            return;
        }

        let msg;

        try {
            msg = JSON.parse(raw.toString('utf8'));
        } catch (e) {
            return send(ws, {
                type: 'error',
                reason: 'invalid-json'
            });
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
                    return send(ws, {
                        type: 'error',
                        reason: 'unknown-message-type'
                    });
            }
        } catch (e) {
            console.error('Error handling message:', e);

            send(ws, {
                type: 'error',
                reason: 'server-error'
            });
        }
    });

    ws.on('close', () => {
        handshakeState.delete(ws);

        const randomId = findConnectedRandomId(ws);

        if (randomId) {
            activeConnections.delete(randomId);
        }

        const connIp = ipByConnection.get(ws);
        ipByConnection.delete(ws);

        if (connIp) {
            const remaining =
                (connectionsByIp.get(connIp) ?? 1) - 1;

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

    if (
        !isValidRandomId(randomId) ||
        typeof identityPublicKey !== 'string'
    ) {
        return send(ws, {
            type: 'error',
            reason: 'invalid-hello'
        });
    }

    let publicKeyBytes;

    try {
        publicKeyBytes = Buffer.from(identityPublicKey, 'base64');

        PublicKey.deserialize(publicKeyBytes);
    } catch (e) {
        return send(ws, {
            type: 'error',
            reason: 'invalid-public-key'
        });
    }

    const existingBinding = identityBindings.get(randomId);

    if (
        existingBinding &&
        !existingBinding.publicKeyBytes.equals(publicKeyBytes)
    ) {
        return send(ws, {
            type: 'error',
            reason: 'identity-mismatch'
        });
    }

    if (!existingBinding) {
        if (identityBindings.size >= MAX_TOTAL_IDENTITY_BINDINGS) {
            return send(ws, {
                type: 'error',
                reason: 'server-at-capacity'
            });
        }

        identityBindings.set(randomId, {
            publicKeyBytes
        });
    }

    const nonce = crypto.randomBytes(32);

    handshakeState.set(ws, {
        randomId,
        publicKeyBytes,
        pendingNonce: nonce,
        nonceExpiresAt: Date.now() + NONCE_TTL_MS
    });

    send(ws, {
        type: 'challenge',
        nonce: nonce.toString('base64')
    });
}

function handleAuth(ws, msg) {
    const state = handshakeState.get(ws);

    if (!state) {
        return send(ws, {
            type: 'error',
            reason: 'no-handshake-in-progress'
        });
    }

    if (Date.now() > state.nonceExpiresAt) {
        handshakeState.delete(ws);

        return send(ws, {
            type: 'error',
            reason: 'challenge-expired'
        });
    }

    const { signature } = msg;

    if (typeof signature !== 'string') {
        return send(ws, {
            type: 'error',
            reason: 'invalid-auth'
        });
    }

    let verified = false;

    try {
        const publicKey = PublicKey.deserialize(
            state.publicKeyBytes
        );

        const signatureBytes = Buffer.from(
            signature,
            'base64'
        );

        verified = publicKey.verify(
            state.pendingNonce,
            signatureBytes
        );
    } catch (e) {
        console.error(
            'Signature verification error:',
            e
        );

        verified = false;
    }

    if (!verified) {
        send(ws, {
            type: 'error',
            reason: 'signature-invalid'
        });

        ws.close();
        return;
    }

    const previous = activeConnections.get(
        state.randomId
    );

    if (previous && previous.ws !== ws) {
        try {
            previous.ws.close();
        } catch (e) {
            // Already closed.
        }
    }

    activeConnections.set(state.randomId, {
        ws
    });

    handshakeState.delete(ws);

    send(ws, {
        type: 'ready'
    });

    flushQueueTo(
        state.randomId,
        ws
    );
}

function flushQueueTo(randomId, ws) {
    const queue = relayQueues.get(randomId);

    if (!queue || queue.length === 0) {
        return;
    }

    const now = Date.now();

    for (const item of queue) {
        if (item.expiresAtEpochMillis < now) {
            continue;
        }

        send(ws, {
            type: 'queued-item',
            id: item.id,
            from: item.from,
            payload: item.payload
        });
    }
}

function handleSignal(ws, msg) {
    const randomId = findConnectedRandomId(ws);

    if (!randomId) {
        return send(ws, {
            type: 'error',
            reason: 'not-authenticated'
        });
    }

    const { to, payload } = msg;

    if (
        !isValidRandomId(to) ||
        payload === undefined
    ) {
        return send(ws, {
            type: 'error',
            reason: 'invalid-signal'
        });
    }

    const payloadSize = Buffer.byteLength(
        JSON.stringify(payload),
        'utf8'
    );

    if (payloadSize > MAX_PAYLOAD_BYTES) {
        return send(ws, {
            type: 'error',
            reason: 'payload-too-large'
        });
    }

    const target = activeConnections.get(to);

    if (target) {
        return send(target.ws, {
            type: 'signal',
            from: randomId,
            payload
        });
    }

    if (
        payload &&
        payload.kind === 'relayed-message'
    ) {
        const queue =
            relayQueues.get(to) ?? [];

        if (
            queue.length >=
            MAX_QUEUE_PER_RECIPIENT
        ) {
            return send(ws, {
                type: 'error',
                reason: 'recipient-queue-full'
            });
        }

        const now = Date.now();

        const item = {
            id: crypto.randomBytes(16).toString('hex'),
            from: randomId,
            payload,
            queuedAtEpochMillis: now,
            expiresAtEpochMillis:
                now + RELAY_EXPIRY_MS
        };

        queue.push(item);

        relayQueues.set(to, queue);

        return send(ws, {
            type: 'queued',
            to,
            id: item.id
        });
    }

    return send(ws, {
        type: 'peer-offline',
        to
    });
}

function handleAck(ws, msg) {
    const randomId = findConnectedRandomId(ws);

    if (!randomId) {
        return send(ws, {
            type: 'error',
            reason: 'not-authenticated'
        });
    }

    const { id } = msg;
    const queue = relayQueues.get(randomId);

    if (!queue) {
        return;
    }

    const filtered = queue.filter(
        (item) => item.id !== id
    );

    if (filtered.length === 0) {
        relayQueues.delete(randomId);
    } else {
        relayQueues.set(
            randomId,
            filtered
        );
    }
}

function findConnectedRandomId(ws) {
    for (
        const [randomId, entry]
        of activeConnections.entries()
    ) {
        if (entry.ws === ws) {
            return randomId;
        }
    }

    return null;
}

function isValidRandomId(id) {
    return (
        typeof id === 'string' &&
        /^[0-9]{12}$/.test(id)
    );
}

function send(ws, obj) {
    if (ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify(obj));
    }
}

setInterval(() => {
    const now = Date.now();

    for (
        const [ip, entry]
        of messageRateByIp.entries()
    ) {
        if (
            now - entry.windowStartedAt >
            IP_RATE_WINDOW_MS
        ) {
            messageRateByIp.delete(ip);
        }
    }
}, IP_RATE_WINDOW_MS);

setInterval(() => {
    const now = Date.now();

    for (
        const [ws, state]
        of handshakeState.entries()
    ) {
        if (
            now > state.nonceExpiresAt
        ) {
            handshakeState.delete(ws);
        }
    }
}, NONCE_TTL_MS);

setInterval(() => {
    const now = Date.now();

    for (
        const [randomId, queue]
        of relayQueues.entries()
    ) {
        const remaining = queue.filter(
            (item) =>
                item.expiresAtEpochMillis >= now
        );

        if (remaining.length === 0) {
            relayQueues.delete(randomId);
        } else if (
            remaining.length !== queue.length
        ) {
            relayQueues.set(
                randomId,
                remaining
            );
        }
    }
}, SWEEP_INTERVAL_MS);
