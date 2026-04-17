const express = require('express');
const multer = require('multer');
const path = require('path');
const { v4: uuidv4 } = require('uuid');
const db = require('../db');
const { enrollFace } = require('../services/face');

const router = express.Router();
const tmpDir = path.join(__dirname, '..', 'uploads', 'tmp');
require('fs').mkdirSync(tmpDir, { recursive: true });

const upload = multer({ dest: tmpDir, limits: { fileSize: 10 * 1024 * 1024 } });

// POST /api/faces/enroll — register a child's face
router.post('/enroll', upload.single('photo'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'No photo uploaded' });
  const { childName } = req.body;
  if (!childName) return res.status(400).json({ error: 'childName is required' });

  const result = await enrollFace(req.file.path, childName.trim());
  require('fs').unlinkSync(req.file.path);

  if (result.error) return res.status(500).json(result);

  db.prepare(`
    INSERT INTO enrolled_faces (id, child_name, embedding)
    VALUES (?, ?, 'stored_in_face_db')
  `).run(uuidv4(), childName.trim());

  res.json({ success: true, childName, message: `Face enrolled for ${childName}` });
});

// GET /api/faces — list enrolled children
router.get('/', (req, res) => {
  const children = db.prepare(`
    SELECT child_name, COUNT(*) as samples, MAX(created_at) as last_enrolled
    FROM enrolled_faces GROUP BY child_name
  `).all();
  res.json(children);
});

// DELETE /api/faces/:childName — remove a child's enrolled faces
router.delete('/:childName', (req, res) => {
  db.prepare(`DELETE FROM enrolled_faces WHERE child_name = ?`).run(req.params.childName);
  // Also remove from face_db.json
  const dbPath = path.join(__dirname, '..', 'data', 'face_db.json');
  if (require('fs').existsSync(dbPath)) {
    const faceDb = JSON.parse(require('fs').readFileSync(dbPath, 'utf8'));
    delete faceDb[req.params.childName];
    require('fs').writeFileSync(dbPath, JSON.stringify(faceDb));
  }
  res.json({ success: true });
});

module.exports = router;
