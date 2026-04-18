/**
 * Admin-only user management.
 * All routes are mounted under /admin/users and protected by ADMIN_TOKEN.
 */
const express = require('express');
const { v4: uuidv4 } = require('uuid');
const db = require('../db');
const { sanitizeText } = require('../utils/sanitize');

const router = express.Router();

// POST /admin/users — create a new user
router.post('/', (req, res) => {
  const username = sanitizeText(req.body.username, 50);
  const token    = sanitizeText(req.body.token,    128);
  if (!username || !token) return res.status(400).json({ error: 'username and token required' });

  const existing = db.prepare('SELECT id FROM users WHERE username = ? OR token = ?').get(username, token);
  if (existing) return res.status(409).json({ error: 'Username or token already exists' });

  const id = uuidv4();
  db.prepare('INSERT INTO users (id, username, token) VALUES (?, ?, ?)').run(id, username, token);
  res.status(201).json({ id, username, message: `User "${username}" created` });
});

// GET /admin/users — list all users (no tokens returned)
router.get('/', (req, res) => {
  const users = db.prepare('SELECT id, username, created_at FROM users ORDER BY created_at DESC').all();
  res.json(users);
});

// DELETE /admin/users/:id — remove a user and all their data (CASCADE)
router.delete('/:id', (req, res) => {
  const result = db.prepare('DELETE FROM users WHERE id = ?').run(req.params.id);
  if (result.changes === 0) return res.status(404).json({ error: 'User not found' });
  res.json({ success: true });
});

module.exports = router;
