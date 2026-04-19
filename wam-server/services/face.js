const { execFile } = require('child_process');
const path = require('path');

const PYTHON    = process.env.PYTHON_PATH || 'python';
const FACE_PY  = path.join(__dirname, 'face.py');
const DEFAULT_DB = path.join(__dirname, '..', 'data', 'face_db_global.json');
const THRESHOLD = process.env.FACE_MATCH_THRESHOLD || '0.5';

function runPython(args) {
  return new Promise((resolve, reject) => {
    execFile(PYTHON, [FACE_PY, ...args], { timeout: 30000 }, (err, stdout, stderr) => {
      if (err) return reject(new Error(stderr || err.message));
      try {
        resolve(JSON.parse(stdout.trim()));
      } catch {
        reject(new Error(`face.py returned invalid JSON: ${stdout}`));
      }
    });
  });
}

/**
 * Enroll a face photo for a named child into a specific DB file.
 * @param {string} imagePath  - absolute path to uploaded image
 * @param {string} childName  - name of the child
 * @param {string} dbPath     - per-user face_db_{userId}.json path
 */
async function enrollFace(imagePath, childName, dbPath) {
  try {
    return await runPython(['enroll', imagePath, childName, dbPath]);
  } catch (e) {
    console.error('[face.js] enrollFace error:', e.message);
    return { error: e.message };
  }
}

/**
 * Recognize faces in an image against a specific DB file.
 * @param {string} imagePath  - absolute path to image
 * @param {string} dbPath     - per-user face_db_{userId}.json path (required)
 */
async function recognizeFaces(imagePath, dbPath = DEFAULT_DB) {
  try {
    return await runPython(['recognize', imagePath, dbPath, THRESHOLD]);
  } catch (e) {
    console.error('[face.js] recognizeFaces error:', e.message);
    return { matches: [], has_children: false, faces_found: 0 };
  }
}

module.exports = { enrollFace, recognizeFaces };