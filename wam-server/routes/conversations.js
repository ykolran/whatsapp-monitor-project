const express = require('express');
const { v4: uuidv4 } = require('uuid');
const db      = require('../db');
const { summarizeConversation } = require('../services/llm');

const router = express.Router();

/**
 * GET /api/conversations
 * Returns ONLY conversations that have at least one new (non-history) message.
 * Each conversation has exactly one live summary (is_history = 0).
 * Swiped conversations disappear until a new message arrives.
 */
router.get('/', (req, res) => {
  const rows = db.prepare(`
    SELECT
      c.id, c.remote_id, c.contact_name, c.is_group,
      c.updated_at, c.message_count,
      s.text        AS summary,
      s.sentiment   AS sentiment,
      s.created_at  AS summary_at,
      COUNT(m.id)   AS new_message_count
    FROM conversations c
    JOIN messages m ON m.conversation_id = c.id AND m.is_history = 0
    LEFT JOIN summaries s ON s.id = (
      SELECT id FROM summaries
      WHERE conversation_id = c.id AND is_history = 0
      ORDER BY created_at DESC LIMIT 1
    )
    WHERE c.user_id = ?
    GROUP BY c.id
    ORDER BY c.updated_at DESC
  `).all(req.user.id);
  res.json(rows);
});

// GET /api/conversations/:id
router.get('/:id', (req, res) => {
  const conv = db.prepare(
    'SELECT * FROM conversations WHERE id = ? AND user_id = ?'
  ).get(req.params.id, req.user.id);
  if (!conv) return res.status(404).json({ error: 'Conversation not found' });
  res.json(conv);
});

/**
 * POST /api/conversations/:id/swipe
 * Archives all new messages and the live summary for this conversation.
 * After this, the conversation vanishes from GET / until a new message arrives.
 */
router.post('/:id/swipe', async (req, res) => {
  const conv = db.prepare(
    'SELECT * FROM conversations WHERE id = ? AND user_id = ?'
  ).get(req.params.id, req.user.id);
  if (!conv) return res.status(404).json({ error: 'Conversation not found' });

  const newMessages = db.prepare(`
    SELECT id, sender, text, timestamp FROM messages
    WHERE conversation_id = ? AND is_history = 0
    ORDER BY timestamp ASC
  `).all(conv.id);

  if (!newMessages.length) {
    return res.json({ success: true, message: 'No new messages to archive' });
  }

  // Build context from the previous history summary (if any)
  const historyRow = db.prepare(`
    SELECT text FROM summaries
    WHERE conversation_id = ? AND is_history = 1
    ORDER BY created_at DESC LIMIT 1
  `).get(conv.id);

  // Generate a final summary of the new batch to store as history context
  const result = await summarizeConversation(
    conv.contact_name, newMessages, historyRow?.text || null
  );

  // Archive the existing live summary (is_history = 0 → 1)
  db.prepare(`
    UPDATE summaries SET is_history = 1
    WHERE conversation_id = ? AND is_history = 0
  `).run(conv.id);

  // Store the swipe-time summary as history context for the next batch
  const summaryId = uuidv4();
  db.prepare(`
    INSERT INTO summaries (id, conversation_id, user_id, text, sentiment, is_history)
    VALUES (?, ?, ?, ?, ?, 1)
  `).run(summaryId, conv.id, req.user.id, result.summary, result.sentiment);

  // Archive all new messages, linking them to this summary
  db.prepare(`
    UPDATE messages SET is_history = 1, history_summary_id = ?
    WHERE conversation_id = ? AND is_history = 0
  `).run(summaryId, conv.id);

  // Tell the Android app to remove this conversation from the list
  const broadcastToUser = req.app.get('broadcastToUser');
  broadcastToUser(req.user.id, {
    type: 'conversation_archived',
    conversationId: conv.id,
    remoteId: conv.remote_id,
  });

  res.json({ success: true });
});

/**
 * POST /api/conversations/:id/resummarize
 * Re-runs the LLM on all current non-history messages and updates the live summary.
 * Does NOT archive anything — the conversation stays visible.
 */
router.post('/:id/resummarize', async (req, res) => {
  const conv = db.prepare(
    'SELECT * FROM conversations WHERE id = ? AND user_id = ?'
  ).get(req.params.id, req.user.id);
  if (!conv) return res.status(404).json({ error: 'Conversation not found' });

  const newMessages = db.prepare(`
    SELECT sender, text, timestamp FROM messages
    WHERE conversation_id = ? AND is_history = 0
    ORDER BY timestamp ASC LIMIT 30
  `).all(conv.id);

  if (!newMessages.length) {
    return res.status(400).json({ error: 'No messages to summarize' });
  }

  const historyRow = db.prepare(`
    SELECT text FROM summaries
    WHERE conversation_id = ? AND is_history = 1
    ORDER BY created_at DESC LIMIT 1
  `).get(conv.id);

  const result = await summarizeConversation(
    conv.contact_name, newMessages, historyRow?.text || null
  );

  // Replace the live summary
  db.prepare(
    'DELETE FROM summaries WHERE conversation_id = ? AND is_history = 0'
  ).run(conv.id);

  const summaryId = uuidv4();
  db.prepare(`
    INSERT INTO summaries (id, conversation_id, user_id, text, sentiment, is_history)
    VALUES (?, ?, ?, ?, ?, 0)
  `).run(summaryId, conv.id, req.user.id, result.summary, result.sentiment);

  // Broadcast the updated summary via WebSocket
  const broadcastToUser = req.app.get('broadcastToUser');
  broadcastToUser(req.user.id, {
    type:           'summary_updated',
    conversationId: conv.id,
    remoteId:       conv.remote_id,
    contactName:    conv.contact_name,
    summary:        result.summary,
    sentiment:      result.sentiment,
    timestamp:      Date.now(),
  });

  res.json({ success: true, summary: result.summary, sentiment: result.sentiment });
});

/**
 * DELETE /api/conversations
 * Deletes ALL conversations, messages, summaries, and images for this user.
 * Irreversible — requires confirmation on the client side.
 */
router.delete('/', (req, res) => {
  // Cascade deletes handle messages and summaries via FK ON DELETE CASCADE
  db.prepare('DELETE FROM conversations WHERE user_id = ?').run(req.user.id);
  db.prepare('DELETE FROM images       WHERE user_id = ?').run(req.user.id);
  res.json({ success: true });
});

module.exports = router;
