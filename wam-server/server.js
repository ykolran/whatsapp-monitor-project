require('dotenv').config();
const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');
const fs = require('fs');

const db = require('./db');
const { handleWebSocket, broadcast } = require('./ws-handler');
const messagesRouter = require('./routes/messages');
const imagesRouter = require('./routes/images');
const facesRouter = require('./routes/faces');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// ── Middleware ──────────────────────────────────────────────
app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(morgan('dev'));

// Serve uploaded images as static files
const uploadsDir = path.join(__dirname, 'uploads');
if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });
app.use('/uploads', express.static(uploadsDir));

// Simple token auth middleware
app.use((req, res, next) => {
  const token = req.headers['x-auth-token'] || req.query.token;
  if (token !== process.env.AUTH_TOKEN) {
    return res.status(401).json({ error: 'Unauthorized' });
  }
  next();
});

// ── Routes ──────────────────────────────────────────────────
app.use('/api/messages', messagesRouter);
app.use('/api/images', imagesRouter);
app.use('/api/faces', facesRouter);

// Health check
app.get('/api/health', (req, res) => res.json({ status: 'ok', timestamp: Date.now() }));

// Get all conversations with their latest summaries
app.get('/api/conversations', (req, res) => {
  const conversations = db.prepare(`
    SELECT c.id, c.contact_name, c.updated_at, c.message_count,
           s.text AS summary, s.created_at AS summary_at
    FROM conversations c
    LEFT JOIN summaries s ON s.id = (
      SELECT id FROM summaries WHERE conversation_id = c.id ORDER BY created_at DESC LIMIT 1
    )
    ORDER BY c.updated_at DESC
  `).all();
  res.json(conversations);
});

// ── WebSocket ───────────────────────────────────────────────
wss.on('connection', (ws, req) => {
  const token = new URL(req.url, 'http://localhost').searchParams.get('token');
  if (token !== process.env.AUTH_TOKEN) { ws.close(4401, 'Unauthorized'); return; }
  handleWebSocket(ws);
});

// Expose broadcast for use in routes
app.set('broadcast', broadcast);

// ── Start ───────────────────────────────────────────────────
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';
server.listen(PORT, HOST, () => {
  console.log(`\n🚀 WhatsApp Mirror Server running on http://${HOST}:${PORT}`);
  console.log(`   → WebSocket: ws://${HOST}:${PORT}`);
  console.log(`   → LM Studio: ${process.env.LM_STUDIO_BASE_URL}`);
  console.log(`   → Auth token: ${process.env.AUTH_TOKEN}`);
});
