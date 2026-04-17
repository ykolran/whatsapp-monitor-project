const express = require('express');
const multer = require('multer');
const path = require('path');
const sharp = require('sharp');
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

  const { conversationId } = req.body;
  const imagePath = req.file.path;
  const imageId = uuidv4();

  // Generate thumbnail
  const thumbFilename = `thumb_${req.file.filename}`;
  const thumbPath = path.join(uploadsDir, thumbFilename);
  await sharp(imagePath).resize(200, 200, { fit: 'cover' }).toFile(thumbPath);

  // Run face recognition
  const faceResult = await recognizeFaces(imagePath);

  // Store in DB
  db.prepare(`
    INSERT INTO images (id, conversation_id, filename, path, has_children, matched_names, thumbnail_path)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `).run(
    imageId, conversationId || null, req.file.filename, imagePath,
    faceResult.has_children ? 1 : 0,
    faceResult.matches.join(','),
    thumbFilename
  );

  const baseUrl = `${req.protocol}://${req.get('host')}`;
  res.json({
    imageId,
    hasChildren: faceResult.has_children,
    matchedNames: faceResult.matches,
    imageUrl: `${baseUrl}/uploads/${req.file.filename}`,
    thumbnailUrl: `${baseUrl}/uploads/${thumbFilename}`,
  });
});

// GET /api/images/children — get all images containing enrolled children
router.get('/children', (req, res) => {
  const images = db.prepare(`
    SELECT * FROM images WHERE has_children = 1 ORDER BY received_at DESC LIMIT 100
  `).all();

  const baseUrl = `${req.protocol}://${req.get('host')}`;
  const result = images.map(img => ({
    ...img,
    imageUrl: `${baseUrl}/uploads/${img.filename}`,
    thumbnailUrl: `${baseUrl}/uploads/${img.thumbnail_path}`,
  }));
  res.json(result);
});

module.exports = router;
