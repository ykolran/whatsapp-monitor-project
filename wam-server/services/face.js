const { spawn } = require('child_process');
const path = require('path');

const PYTHON = process.env.PYTHON_PATH || 'python';
const SCRIPT = path.join(__dirname, 'face.py');
const DB_PATH = path.join(__dirname, '..', 'data', 'face_db.json');
const THRESHOLD = process.env.FACE_MATCH_THRESHOLD || '0.5';

function runPython(args) {
  return new Promise((resolve, reject) => {
    const proc = spawn(PYTHON, [SCRIPT, ...args]);
    let stdout = '';
    let stderr = '';
    proc.stdout.on('data', d => stdout += d);
    proc.stderr.on('data', d => stderr += d);
    proc.on('close', (code) => {
      try {
        resolve(JSON.parse(stdout.trim()));
      } catch {
        reject(new Error(`Python error (exit ${code}): ${stderr || stdout}`));
      }
    });
    proc.on('error', reject);
  });
}

async function enrollFace(imagePath, childName) {
  return runPython(['enroll', imagePath, childName, DB_PATH]);
}

async function recognizeFaces(imagePath) {
  try {
    return await runPython(['recognize', imagePath, DB_PATH, THRESHOLD]);
  } catch (err) {
    console.error('[Face] Recognition error:', err.message);
    return { matches: [], has_children: false, error: err.message };
  }
}

module.exports = { enrollFace, recognizeFaces };
