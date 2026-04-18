const express = require('express');
const { v4: uuidv4 } = require('uuid');
const db = require('../db');
const { summarizeConversation } = require('../services/llm');

const router = express.Router();

// GET /api/conversations — all conversations for the authenticated user
router.get('/', (req, res) => {
  const rows = db.prepare(`
    SELECT c.id, c.remote_id, c.contact_name, c.is_group, c.updated_at, c.message_count,
           s.text      AS summary,
           s.sentiment AS sentiment,
           s.created_at AS summary_at,
           (SELECT COUNT(*) FROM messages m
            WHERE m.conversation_id = c.id AND m.is_history = 0) AS new_message_count
    FROM conversations c
    LEFT JOIN summaries s ON s.id = (
      SELECT id FROM summaries
      WHERE conversation_id = c.id AND is_history = 0
      ORDER BY created_at DESC LIMIT 1
    )
    WHERE c.user_id = ?
    ORDER BY c.updated_at DESC
  `).all(req.user.id);
  res.json(rows);
});

// GET /api/conversations/:id — single conversation detail
router.get('/:id', (req, res) => {
  const conv = db.prepare(
    'SELECT * FROM conversations WHERE id = ? AND user_id = ?'
  ).get(req.params.id, req.user.id);
  if (!conv) return res.status(404).json({ error: 'Conversation not found' });
  res.json(conv);
});

/**
 * POST /api/conversations/:id/swipe
 * Marks all current new messages as history, triggers a fresh LLM summary with context,
 * and broadcasts the update to the user's connected clients.
 */
router.post('/:id/swipe', async (req, res) => {
  const conv = db.prepare(
    'SELECT * FROM conversations WHERE id = ? AND user_id = ?'
  ).get(req.params.id, req.user.id);
  if (!conv) return res.status(404).json({ error: 'Conversation not found' });

  // Collect new (non-history) messages
  const newMessages = db.prepare(`
    SELECT id, sender, text, timestamp FROM messages
    WHERE conversation_id = ? AND is_history = 0
    ORDER BY timestamp ASC
  `).all(conv.id);

  if (!newMessages.length) {
    return res.json({ success: true, message: 'No new messages to archive' });
  }

  // Fetch the most recent history summary as context
  const historyRow = db.prepare(`
    SELECT text FROM summaries
    WHERE conversation_id = ? AND is_history = 1
    ORDER BY created_at DESC LIMIT 1
  `).get(conv.id);

  const historyContext = historyRow?.text || null;

  // Generate summary
  const result = await summarizeConversation(conv.contact_name, newMessages, historyContext);

  // Store the new summary (is_history = 1 because it will become past context on next swipe)
  const summaryId = uuidv4();
  db.prepare(`
    INSERT INTO summaries (id, conversation_id, user_id, text, sentiment, is_history)
    VALUES (?, ?, ?, ?, ?, 1)
  `).run(summaryId, conv.id, req.user.id, result.summary, result.sentiment);

  // Mark all new messages as history
  db.prepare(`
    UPDATE messages SET is_history = 1, history_summary_id = ?
    WHERE conversation_id = ? AND is_history = 0
  `).run(summaryId, conv.id);

  // Broadcast to user's WS clients
  const broadcastToUser = req.app.get('broadcastToUser');
  broadcastToUser(req.user.id, {
    type: 'conversation_swiped',
    conversationId: conv.id,
    remoteId: conv.remote_id,
    contactName: conv.contact_name,
    summary: result.summary,
    sentiment: result.sentiment,
    timestamp: Date.now(),
  });

  res.json({ success: true, summary: result.summary, sentiment: result.sentiment });
});

module.exports = router;
