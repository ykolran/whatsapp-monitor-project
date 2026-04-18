const { spawn } = require('child_process');
const path = require('path');

const PYTHON    = process.env.PYTHON_PATH || 'python';
const SCRIPT    = path.join(__dirname, 'face.py');
const THRESHOLD = process.env.FACE_MATCH_THRESHOLD || '0.5';

function runPython(args) {
  return new Promise((resolve, reject) => {
    const proc = spawn(PYTHON, [SCRIPT, ...args]);
    let stdout = '', stderr = '';
    proc.stdout.on('data', d => stdout += d);
    proc.stderr.on('data', d => stderr += d);
    proc.on('close', (code) => {
      try { resolve(JSON.parse(stdout.trim())); }
      catch { reject(new Error(`Python error (exit ${code}): ${stderr || stdout}`)); }
    });
    proc.on('error', reject);
  });
}

async function enrollFace(imagePath, childName, dbPath) {
  return runPython(['enroll', imagePath, childName, dbPath]);
}

async function recognizeFaces(imagePath, dbPath) {
  try { return await runPython(['recognize', imagePath, dbPath, THRESHOLD]); }
  catch (err) {
    console.error('[Face] Recognition error:', err.message);
    return { matches: [], has_children: false, error: err.message };
  }
}

module.exports = { enrollFace, recognizeFaces };
