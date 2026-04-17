const clients = new Set();

function handleWebSocket(ws) {
  clients.add(ws);
  console.log(`[WS] Client connected. Total: ${clients.size}`);

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
    clients.delete(ws);
    console.log(`[WS] Client disconnected. Total: ${clients.size}`);
  });

  ws.on('error', (err) => {
    console.error('[WS] Error:', err.message);
    clients.delete(ws);
  });

  ws.send(JSON.stringify({ type: 'connected', timestamp: Date.now() }));
}

function broadcast(payload) {
  const data = JSON.stringify(payload);
  let count = 0;
  clients.forEach((ws) => {
    if (ws.readyState === 1) { ws.send(data); count++; }
    else clients.delete(ws);
  });
  if (count > 0) console.log(`[WS] Broadcast '${payload.type}' to ${count} client(s)`);
}

module.exports = { handleWebSocket, broadcast };
