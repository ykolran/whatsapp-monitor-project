#!/usr/bin/env python3
"""
Face recognition microservice — powered by InsightFace (buffalo_l model).

Usage:
  python face.py enroll    <image_path> <child_name> <db_path>
  python face.py recognize <image_path> <db_path> [threshold]

Returns JSON to stdout. Warnings go to stderr and are filtered by face.js.
First run downloads buffalo_l model (~300 MB) to ~/.insightface/models/
"""

import sys
import json
import os
import warnings

# Suppress FutureWarnings from insightface internals before importing
warnings.filterwarnings('ignore', category=FutureWarning)
warnings.filterwarnings('ignore', category=UserWarning)

# Silence tqdm progress bar (model download) — redirect to stderr so stdout stays clean JSON
os.environ.setdefault('TQDM_DISABLE', '0')  # keep progress on stderr, not stdout

import numpy as np
import cv2

_app = None

def get_app():
    global _app
    if _app is None:
        from insightface.app import FaceAnalysis
        # Redirect tqdm output to stderr explicitly
        import sys as _sys
        _app = FaceAnalysis(name='buffalo_l', providers=['CPUExecutionProvider'])
        _app.prepare(ctx_id=-1, det_size=(640, 640))
    return _app


def load_db(db_path):
    if os.path.exists(db_path):
        with open(db_path, 'r', encoding='utf-8') as f:
            return json.load(f)
    return {}

def save_db(db_path, db):
    os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)
    with open(db_path, 'w', encoding='utf-8') as f:
        json.dump(db, f)

def cosine_sim(a, b):
    return float(np.dot(a, b))

def output(data):
    """Write JSON result to stdout — the only thing that goes to stdout."""
    sys.stdout.write(json.dumps(data) + '\n')
    sys.stdout.flush()


def enroll(image_path, child_name, db_path):
    img = cv2.imread(image_path)
    if img is None:
        output({"success": False, "error": f"Cannot read image: {image_path}"})
        return

    faces = get_app().get(img)
    if not faces:
        output({"success": False, "error": "No face detected in image"})
        return

    face = max(faces, key=lambda f: (f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1]))
    embedding = face.normed_embedding.tolist()

    db = load_db(db_path)
    if child_name not in db:
        db[child_name] = []
    db[child_name].append(embedding)
    save_db(db_path, db)

    output({
        "success": True,
        "name": child_name,
        "total_samples": len(db[child_name]),
        "faces_in_photo": len(faces)
    })


def recognize(image_path, db_path, threshold=0.55):
    db = load_db(db_path)
    if not db:
        output({"matches": [], "has_children": False, "faces_found": 0})
        return

    img = cv2.imread(image_path)
    if img is None:
        output({"matches": [], "has_children": False, "faces_found": 0,
                "error": f"Cannot read image: {image_path}"})
        return

    faces = get_app().get(img)
    if not faces:
        output({"matches": [], "has_children": False, "faces_found": 0})
        return

    known = []
    for name, samples in db.items():
        for emb in samples:
            known.append((name, np.array(emb, dtype=np.float32)))

    matched = {}
    for face in faces:
        query = np.array(face.normed_embedding, dtype=np.float32)
        for name, emb in known:
            sim = cosine_sim(query, emb)
            if sim >= threshold:
                if name not in matched or sim > matched[name]:
                    matched[name] = round(sim, 4)

    output({
        "matches": list(matched.keys()),
        "scores": matched,
        "has_children": len(matched) > 0,
        "faces_found": len(faces)
    })


if __name__ == '__main__':
    if len(sys.argv) < 4:
        output({"error": "Usage: face.py enroll|recognize <image> <db> [threshold]"})
        sys.exit(1)

    mode       = sys.argv[1]
    image_path = sys.argv[2]
    db_path    = sys.argv[3]

    try:
        if mode == 'enroll':
            child = sys.argv[4] if len(sys.argv) > 4 else 'unknown'
            enroll(image_path, child, db_path)
        elif mode == 'recognize':
            thresh = float(sys.argv[4]) if len(sys.argv) > 4 else 0.55
            recognize(image_path, db_path, thresh)
        else:
            output({"error": f"Unknown mode: {mode}"})
            sys.exit(1)
    except Exception as e:
        output({"error": str(e)})
        sys.exit(1)
