// Per-user WebSocket client registry
const clients = new Map(); // userId -> Set<WebSocket>

function handleWebSocket(ws, userId) {
  if (!clients.has(userId)) clients.set(userId, new Set());
  clients.get(userId).add(ws);
  console.log(`[WS] User ${userId} connected (${clients.get(userId).size} sessions)`);

  ws.on('message', (data) => {
    try {
      const msg = JSON.parse(data.toString());
      console.log(`[WS] Received:`, msg.type);
      // Ping-pong keepalive
      if (msg.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    } catch (e) {
      console.error('[WS] Parse error:', e.message);
    }
  });

  ws.on('close', () => {
    clients.get(userId)?.delete(ws);
    console.log(`[WS] User ${userId} disconnected`);
  });

  ws.on('error', (err) => {
    console.error('[WS] Error:', err.message);
    clients.get(userId)?.delete(ws);
  });

  ws.send(JSON.stringify({ type: 'connected', timestamp: Date.now() }));
}

function broadcastToUser(userId, payload) {
  const userSessions = clients.get(userId);
  if (!userSessions?.size) return;
  const data = JSON.stringify(payload);
  userSessions.forEach((ws) => {
    if (ws.readyState === 1) ws.send(data);
    else userSessions.delete(ws);
  });
}

module.exports = { handleWebSocket, broadcastToUser };
