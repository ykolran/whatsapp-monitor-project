require('dotenv').config();
const express = require('express');
const http = require('http');
const { WebSocketServer } = require('ws');
const cors = require('cors');
const morgan = require('morgan');
const path = require('path');
const fs = require('fs');

const db = require('./db');
const { handleWebSocket, broadcastToUser } = require('./ws-handler');
const messagesRouter     = require('./routes/messages');
const imagesRouter       = require('./routes/images');
const facesRouter        = require('./routes/faces');
const conversationsRouter = require('./routes/conversations');
const usersRouter        = require('./routes/users');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// ── Middleware ──────────────────────────────────────────────────────
app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use(morgan('dev'));

// Serve uploaded images as static files
const uploadsDir = path.join(__dirname, 'uploads');
if (!fs.existsSync(uploadsDir)) fs.mkdirSync(uploadsDir, { recursive: true });
app.use('/uploads', express.static(uploadsDir));

// ── Admin-only user management (separate token) ─────────────────────
app.use('/admin', (req, res, next) => {
  const token = req.headers['x-admin-token'];
  if (!token || token !== process.env.ADMIN_TOKEN) {
    return res.status(401).json({ error: 'Admin token required' });
  }
  next();
});
app.use('/admin/users', usersRouter);

// ── Per-user auth middleware ────────────────────────────────────────
// Resolves token → user row and attaches to req.user
app.use((req, res, next) => {
  const token = req.headers['x-auth-token'] || req.query.token;
  if (!token) return res.status(401).json({ error: 'Missing auth token' });

  const user = db.prepare('SELECT * FROM users WHERE token = ?').get(token);
  if (!user) return res.status(401).json({ error: 'Invalid auth token' });

  req.user = user;
  next();
});

// ── Routes ──────────────────────────────────────────────────────────
app.use('/api/messages',      messagesRouter);
app.use('/api/images',        imagesRouter);
app.use('/api/faces',         facesRouter);
app.use('/api/conversations', conversationsRouter);

app.get('/api/health', (req, res) =>
  res.json({ status: 'ok', user: req.user.username, timestamp: Date.now() })
);

// ── WebSocket ────────────────────────────────────────────────────────
wss.on('connection', (ws, req) => {
  const token = new URL(req.url, 'http://localhost').searchParams.get('token');
  const user = db.prepare('SELECT * FROM users WHERE token = ?').get(token);
  if (!user) { ws.close(4401, 'Unauthorized'); return; }
  handleWebSocket(ws, user.id);
});

app.set('broadcastToUser', broadcastToUser);

// ── Start ────────────────────────────────────────────────────────────
const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';
server.listen(PORT, HOST, () => {
  console.log(`\n🚀 WhatsApp Mirror Server  http://${HOST}:${PORT}`);
  console.log(`   → LM Studio : ${process.env.LM_STUDIO_BASE_URL}`);
  console.log(`   → Admin token required for /admin/users\n`);
});
