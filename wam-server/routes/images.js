const express = require('express');
const multer  = require('multer');
const path    = require('path');
const sharp   = require('sharp');
const { v4: uuidv4 } = require('uuid');
const db = require('../db');
const { recognizeFaces } = require('../services/face');

const router = express.Router();
const uploadsDir = path.join(__dirname, '..', 'uploads');

const storage = multer.diskStorage({
  destination: uploadsDir,
  filename: (req, file, cb) => cb(null, `${uuidv4()}${path.extname(file.originalname)}`),
});
const upload = multer({ storage, limits: { fileSize: 20 * 1024 * 1024 } });

// POST /api/images — upload a WhatsApp image for face analysis
router.post('/', upload.single('image'), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'No image uploaded' });

  const convRemoteId = req.body.conversationId || null;
  let convDbId = null;
  if (convRemoteId) {
    const conv = db.prepare(
      'SELECT id FROM conversations WHERE user_id = ? AND remote_id = ?'
    ).get(req.user.id, convRemoteId);
    convDbId = conv?.id || null;
  }

  // Generate thumbnail
  const thumbFilename = `thumb_${req.file.filename}`;
  const thumbPath = path.join(uploadsDir, thumbFilename);
  await sharp(req.file.path).resize(200, 200, { fit: 'cover' }).toFile(thumbPath);

  // ── FIX: use per-user face DB ──────────────────────────────────────
  const userDbPath = path.join(__dirname, '..', 'data', `face_db_${req.user.id}.json`);
  const faceResult = await recognizeFaces(req.file.path, userDbPath);
  // ──────────────────────────────────────────────────────────────────

  const imageId = uuidv4();
  db.prepare(`
    INSERT INTO images (id, user_id, conversation_id, filename, path, has_children, matched_names, thumbnail_path)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `).run(imageId, req.user.id, convDbId, req.file.filename, req.file.path,
         faceResult.has_children ? 1 : 0,
         faceResult.matches.join(','), thumbFilename);

  const base = `${req.protocol}://${req.get('host')}`;
  res.json({
    imageId,
    hasChildren: faceResult.has_children,
    matchedNames: faceResult.matches,
    imageUrl: `${base}/uploads/${req.file.filename}`,
    thumbnailUrl: `${base}/uploads/${thumbFilename}`,
  });
});

// GET /api/images/children — get all images containing enrolled children
router.get('/children', (req, res) => {
  const images = db.prepare(
    'SELECT * FROM images WHERE user_id = ? AND has_children = 1 ORDER BY received_at DESC LIMIT 100'
  ).all(req.user.id);
  const base = `${req.protocol}://${req.get('host')}`;
  res.json(images.map(img => ({
    ...img,
    imageUrl: `${base}/uploads/${img.filename}`,
    thumbnailUrl: `${base}/uploads/${img.thumbnail_path}`,
  })));
});

module.exports = router;