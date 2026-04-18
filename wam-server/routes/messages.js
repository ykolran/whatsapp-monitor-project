const express = require('express');
const { v4: uuidv4 } = require('uuid');
const db = require('../db');
const { summarizeConversation } = require('../services/llm');
const { sanitizeMessagePayload } = require('../utils/sanitize');

const router = express.Router();

// POST /api/messages — ingest a message from the Android app
router.post('/', async (req, res) => {
  const payload = sanitizeMessagePayload(req.body);

  if (!payload.conversationId || !payload.text) {
    return res.status(400).json({ error: 'conversationId and text are required' });
  }

  // Upsert conversation (keyed by user_id + remote_id)
  let conv = db.prepare(
    'SELECT * FROM conversations WHERE user_id = ? AND remote_id = ?'
  ).get(req.user.id, payload.conversationId);

  if (!conv) {
    const convId = uuidv4();
    db.prepare(`
      INSERT INTO conversations (id, user_id, remote_id, contact_name, is_group, message_count)
      VALUES (?, ?, ?, ?, ?, 1)
    `).run(convId, req.user.id, payload.conversationId,
           payload.contactName || 'Unknown', payload.isGroup ? 1 : 0);
    conv = db.prepare('SELECT * FROM conversations WHERE id = ?').get(convId);
  } else {
    db.prepare(`
      UPDATE conversations SET
        contact_name = ?, message_count = message_count + 1, updated_at = unixepoch()
      WHERE id = ?
    `).run(payload.contactName || conv.contact_name, conv.id);
  }

  // Insert message
  const msgId = uuidv4();
  db.prepare(`
    INSERT INTO messages (id, conversation_id, user_id, sender, text, timestamp)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run(msgId, conv.id, req.user.id,
         payload.sender || payload.contactName || 'Unknown',
         payload.text, payload.timestamp);

  res.json({ success: true, messageId: msgId, conversationId: conv.id });

  // Async LLM summary — with history context, only new messages
  setImmediate(async () => {
    try {
      const newMessages = db.prepare(`
        SELECT sender, text, timestamp FROM messages
        WHERE conversation_id = ? AND is_history = 0
        ORDER BY timestamp ASC LIMIT 30
      `).all(conv.id);

      const historyRow = db.prepare(`
        SELECT text FROM summaries
        WHERE conversation_id = ? AND is_history = 1
        ORDER BY created_at DESC LIMIT 1
      `).get(conv.id);

      const result = await summarizeConversation(
        payload.contactName, newMessages, historyRow?.text || null
      );

      // Store as non-history live summary (is_history = 0) — replaced on each new message
      // First delete existing live summary for this conversation
      db.prepare(
        'DELETE FROM summaries WHERE conversation_id = ? AND is_history = 0'
      ).run(conv.id);

      const summaryId = uuidv4();
      db.prepare(`
        INSERT INTO summaries (id, conversation_id, user_id, text, sentiment, is_history)
        VALUES (?, ?, ?, ?, ?, 0)
      `).run(summaryId, conv.id, req.user.id, result.summary, result.sentiment);

      const broadcastToUser = req.app.get('broadcastToUser');
      broadcastToUser(req.user.id, {
        type: 'summary_updated',
        conversationId: conv.id,
        remoteId: conv.remote_id,
        contactName: payload.contactName,
        summary: result.summary,
        sentiment: result.sentiment,
        timestamp: Date.now(),
      });
    } catch (err) {
      console.error('[Messages] Summary failed:', err.message);
    }
  });
});

// GET /api/messages/:conversationId — message history scoped to user
router.get('/:conversationId', (req, res) => {
  const conv = db.prepare(
    'SELECT id FROM conversations WHERE id = ? AND user_id = ?'
  ).get(req.params.conversationId, req.user.id);
  if (!conv) return res.status(404).json({ error: 'Conversation not found' });

  const messages = db.prepare(`
    SELECT id, sender, text, timestamp, is_history, history_summary_id, received_at
    FROM messages WHERE conversation_id = ?
    ORDER BY timestamp ASC LIMIT 200
  `).all(conv.id);
  res.json(messages);
});

module.exports = router;
