const { execFile } = require('child_process');
const path = require('path');

const rawPython = process.env.PYTHON_PATH || 'python';
const PYTHON    = path.isAbsolute(rawPython)
  ? rawPython
  : path.resolve(__dirname, '..', rawPython);

const FACE_PY    = path.join(__dirname, 'face.py');
const DEFAULT_DB = path.join(__dirname, '..', 'data', 'face_db_global.json');
const THRESHOLD  = process.env.FACE_MATCH_THRESHOLD || '0.55';

// First run downloads ~300 MB model — allow 3 minutes
const TIMEOUT_MS = 3 * 60 * 1000;

const STDERR_FILTERS = [
  'UserWarning', 'FutureWarning', 'check_version', 'fetch_version',
  'albumentations', 'rcond', 'tform.estimate', 'face_align', 'transform.py',
  'KB/s', '|##', 'it/s', 'warnings.warn', 'P = np.', 'tform.',
];

function isSuppressed(line) {
  return STDERR_FILTERS.some(f => line.includes(f));
}

/**
 * InsightFace prints model-loading info to stdout before our JSON result.
 * Extract the last line that parses as valid JSON.
 */
function extractJson(stdout) {
  const lines = (stdout || '').trim().split('\n');
  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i].trim();
    if (line.startsWith('{') || line.startsWith('[')) {
      try { return JSON.parse(line); } catch { /* try next */ }
    }
  }
  return null;
}

function runPython(args) {
  return new Promise((resolve, reject) => {
    execFile(PYTHON, [FACE_PY, ...args], { timeout: TIMEOUT_MS }, (err, stdout, stderr) => {
      if (stderr && stderr.trim()) {
        const meaningful = stderr.trim().split('\n').filter(l => l.trim() && !isSuppressed(l));
        if (meaningful.length > 0) {
          console.warn('[face.js] stderr:', meaningful.join('\n'));
        }
      }

      const result = extractJson(stdout);
      if (result) {
        if (result.error && !result.success && result.matches === undefined) {
          return reject(new Error(result.error));
        }
        return resolve(result);
      }

      if (err) {
        const isTimeout = err.killed || err.code === 'ETIMEDOUT';
        console.error(`[face.js] ${isTimeout ? 'TIMEOUT' : 'error'} — Python: ${PYTHON}`);
        return reject(new Error(isTimeout
          ? 'face.py timed out — model may still be downloading, retry in a moment'
          : (stderr || err.message)));
      }

      reject(new Error(`face.py produced no JSON output.\nstdout: ${stdout}\nstderr: ${stderr}`));
    });
  });
}

async function enrollFace(imagePath, childName, dbPath) {
  try {
    return await runPython(['enroll', imagePath, dbPath, childName]);
  } catch (e) {
    console.error('[face.js] enrollFace error:', e.message);
    return { error: e.message };
  }
}

async function recognizeFaces(imagePath, dbPath = DEFAULT_DB) {
  try {
    return await runPython(['recognize', imagePath, dbPath, THRESHOLD]);
  } catch (e) {
    console.error('[face.js] recognizeFaces error:', e.message);
    return { matches: [], has_children: false, faces_found: 0 };
  }
}

module.exports = { enrollFace, recognizeFaces };
