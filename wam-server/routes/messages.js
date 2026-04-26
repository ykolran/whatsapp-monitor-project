const express = require('express');
const { v4: uuidv4 } = require('uuid');
const db      = require('../db');
const { summarizeConversation } = require('../services/llm');
const { sanitizeMessagePayload } = require('../utils/sanitize');

const router = express.Router();

// Per-conversation debounce timers: conversationId -> setTimeout handle
const summaryTimers = new Map();
const DEBOUNCE_MS   = 3000; // wait 3 s after last message before calling LLM

/**
 * Schedule (or reschedule) an LLM summary for a conversation.
 * Any message arriving within DEBOUNCE_MS of the previous one resets the timer,
 * so only ONE summary is generated per burst of messages.
 */
function scheduleSummary(convId, contactName, userId, broadcastFn) {
  // Cancel any pending timer for this conversation
  if (summaryTimers.has(convId)) {
    clearTimeout(summaryTimers.get(convId));
  }

  const handle = setTimeout(async () => {
    summaryTimers.delete(convId);
    try {
      // Read ALL non-history messages accumulated so far
      const newMessages = db.prepare(`
        SELECT sender, text, timestamp FROM messages
        WHERE conversation_id = ? AND is_history = 0
        ORDER BY timestamp ASC LIMIT 30
      `).all(convId);

      if (!newMessages.length) return;

      // Use the most recent history summary as context
      const historyRow = db.prepare(`
        SELECT text FROM summaries
        WHERE conversation_id = ? AND is_history = 1
        ORDER BY created_at DESC LIMIT 1
      `).get(convId);

      const result = await summarizeConversation(
        contactName, newMessages, historyRow?.text || null
      );

      // Replace the single live summary for this conversation
      db.prepare(
        'DELETE FROM summaries WHERE conversation_id = ? AND is_history = 0'
      ).run(convId);

      const summaryId = uuidv4();
      db.prepare(`
        INSERT INTO summaries (id, conversation_id, user_id, text, sentiment, is_history)
        VALUES (?, ?, ?, ?, ?, 0)
      `).run(summaryId, convId, userId, result.summary, result.sentiment);

      // Fetch remoteId for the broadcast
      const conv = db.prepare('SELECT remote_id FROM conversations WHERE id = ?').get(convId);

      broadcastFn(userId, {
        type:           'summary_updated',
        conversationId: convId,
        remoteId:       conv?.remote_id ?? '',
        contactName:    contactName,
        summary:        result.summary,
        sentiment:      result.sentiment,
        timestamp:      Date.now(),
      });

      console.log(`[Messages] Summary updated for "${contactName}" (${newMessages.length} msgs)`);
    } catch (err) {
      console.error('[Messages] Summary failed:', err.message);
    }
  }, DEBOUNCE_MS);

  summaryTimers.set(convId, handle);
}

// POST /api/messages — ingest a message from the Android app
router.post('/', (req, res) => {
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
    conv = db.prepare('SELECT * FROM conversations WHERE id = ?').get(conv.id);
  }

  // Check for duplicate message (same sender, timestamp, and conversation)
  const sender = payload.sender || payload.contactName || 'Unknown';
  const existingMsg = db.prepare(`
    SELECT id FROM messages
    WHERE conversation_id = ? AND sender = ? AND timestamp = ?
  `).get(conv.id, sender, payload.timestamp);

  if (existingMsg) {
    // Duplicate found, skip insertion
    return res.json({ success: true, messageId: existingMsg.id, conversationId: payload.conversationId, isDuplicate: true });
  }

  // Insert message synchronously — respond immediately
  const msgId = uuidv4();
  db.prepare(`
    INSERT INTO messages (id, conversation_id, user_id, sender, text, timestamp)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run(msgId, conv.id, req.user.id, sender, payload.text, payload.timestamp);

  // Respond before LLM runs
  res.json({ success: true, messageId: msgId, conversationId: conv.id });

  // Debounced summary — resets timer on every new message in the same conversation
  const broadcastToUser = req.app.get('broadcastToUser');
  scheduleSummary(conv.id, payload.contactName || conv.contact_name, req.user.id, broadcastToUser);
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
