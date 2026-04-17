const express = require('express');
const { v4: uuidv4 } = require('uuid');
const db = require('../db');
const { summarizeConversation } = require('../services/llm');

const router = express.Router();

// POST /api/messages — receive a new message from Android
router.post('/', async (req, res) => {
  const { conversationId, contactName, sender, text, timestamp, isGroup } = req.body;

  if (!conversationId || !text) {
    return res.status(400).json({ error: 'conversationId and text are required' });
  }

  // Upsert conversation
  db.prepare(`
    INSERT INTO conversations (id, contact_name, is_group, message_count, updated_at)
    VALUES (?, ?, ?, 1, unixepoch())
    ON CONFLICT(id) DO UPDATE SET
      contact_name = excluded.contact_name,
      message_count = message_count + 1,
      updated_at = unixepoch()
  `).run(conversationId, contactName || 'Unknown', isGroup ? 1 : 0);

  // Insert message
  const msgId = uuidv4();
  db.prepare(`
    INSERT INTO messages (id, conversation_id, sender, text, timestamp)
    VALUES (?, ?, ?, ?, ?)
  `).run(msgId, conversationId, sender || contactName || 'Unknown', text, timestamp || Math.floor(Date.now() / 1000));

  res.json({ success: true, messageId: msgId });

  // Async: generate LLM summary (don't block response)
  setImmediate(async () => {
    try {
      const recentMsgs = db.prepare(`
        SELECT sender, text, timestamp FROM messages
        WHERE conversation_id = ?
        ORDER BY timestamp DESC LIMIT 20
      `).all(conversationId).reverse();

      const result = await summarizeConversation(contactName, recentMsgs);

      const summaryId = uuidv4();
      db.prepare(`
        INSERT INTO summaries (id, conversation_id, text, intent, sentiment)
        VALUES (?, ?, ?, ?, ?)
      `).run(summaryId, conversationId, result.summary, result.intent, result.sentiment);

      // Push update to all connected WebSocket clients
      const broadcast = req.app.get('broadcast');
      broadcast({
        type: 'summary_updated',
        conversationId,
        contactName,
        summary: result.summary,
        intent: result.intent,
        sentiment: result.sentiment,
        timestamp: Date.now(),
      });
    } catch (err) {
      console.error('[Messages] Summary generation failed:', err.message);
    }
  });
});

// GET /api/messages/:conversationId — fetch message history
router.get('/:conversationId', (req, res) => {
  const messages = db.prepare(`
    SELECT * FROM messages WHERE conversation_id = ?
    ORDER BY timestamp ASC LIMIT 100
  `).all(req.params.conversationId);
  res.json(messages);
});

module.exports = router;
