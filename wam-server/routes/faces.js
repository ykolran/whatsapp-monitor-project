const express = require('express');
const multer  = require('multer');
const path    = require('path');
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const db = require('../db');
const { enrollFace } = require('../services/face');
const { sanitizeText } = require('../utils/sanitize');

const router = express.Router();
const tmpDir = path.join(__dirname, '..', 'uploads', 'tmp');
fs.mkdirSync(tmpDir, { recursive: true });

const upload = multer({ dest: tmpDir, limits: { fileSize: 10 * 1024 * 1024 } });

// POST /api/faces/enroll — register a child's face
router.post('/enroll', upload.single('photo'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'No photo uploaded' });
  const childName = sanitizeText(req.body.childName, 50);
  if (!childName) return res.status(400).json({ error: 'childName is required' });

  // Use per-user face DB: data/face_db_{userId}.json
  const userDbPath = path.join(__dirname, '..', 'data', `face_db_${req.user.id}.json`);
  const result = await enrollFace(req.file.path, childName, userDbPath);
  fs.unlinkSync(req.file.path);

  if (result.error) return res.status(500).json(result);

  db.prepare('INSERT OR IGNORE INTO enrolled_faces (id, user_id, child_name, embedding) VALUES (?,?,?,?)')
    .run(uuidv4(), req.user.id, childName, 'stored_in_face_db');

  res.json({ success: true, childName, message: `Face enrolled for "${childName}"` });
});

// GET /api/faces — list enrolled children
router.get('/', (req, res) => {
  const children = db.prepare(`
    SELECT child_name, COUNT(*) as samples, MAX(created_at) as last_enrolled
    FROM enrolled_faces WHERE user_id = ? GROUP BY child_name
  `).all(req.user.id);
  res.json(children);
});

// DELETE /api/faces/:childName — remove a child's enrolled faces
router.delete('/:childName', (req, res) => {
  const name = sanitizeText(req.params.childName, 50);
  db.prepare('DELETE FROM enrolled_faces WHERE user_id = ? AND child_name = ?').run(req.user.id, name);
  const userDbPath = path.join(__dirname, '..', 'data', `face_db_${req.user.id}.json`);
  if (fs.existsSync(userDbPath)) {
    const faceDb = JSON.parse(fs.readFileSync(userDbPath, 'utf8'));
    delete faceDb[name];
    fs.writeFileSync(userDbPath, JSON.stringify(faceDb));
  }
  res.json({ success: true });
});

module.exports = router;
