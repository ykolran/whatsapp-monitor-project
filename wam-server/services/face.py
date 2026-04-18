#!/usr/bin/env python3
"""
Face recognition microservice for the WhatsApp Mirror server.
Usage:
  python face.py enroll    <image_path> <child_name> <db_path>
  python face.py recognize <image_path> <db_path> <threshold>

Returns JSON to stdout.
Install: pip install face_recognition numpy
"""
import sys, json, os
import numpy as np

try:
    import face_recognition
except ImportError:
    print(json.dumps({"error": "face_recognition not installed. Run: pip install face_recognition"}))
    sys.exit(1)

def load_db(db_path):
    return json.load(open(db_path)) if os.path.exists(db_path) else {}

def save_db(db_path, db):
    with open(db_path, 'w') as f:
        json.dump(db, f)

def enroll(image_path, child_name, db_path):
    img = face_recognition.load_image_file(image_path)
    encodings = face_recognition.face_encodings(img)
    if not encodings:
        print(json.dumps({"success": False, "error": "No face detected in image"}))
        return
    db = load_db(db_path)
    if child_name not in db:
        db[child_name] = []
    db[child_name].append(encodings[0].tolist())
    save_db(db_path, db)
    print(json.dumps({"success": True, "name": child_name, "total_samples": len(db[child_name])}))

def recognize(image_path, db_path, threshold=0.5):
    db = load_db(db_path)
    if not db:
        print(json.dumps({"matches": [], "has_children": False, "faces_found": 0}))
        return
    img = face_recognition.load_image_file(image_path)
    face_locs = face_recognition.face_locations(img)
    encodings = face_recognition.face_encodings(img, face_locs)
    if not encodings:
        print(json.dumps({"matches": [], "has_children": False, "faces_found": 0}))
        return
    known_names, known_encodings = [], []
    for name, samples in db.items():
        for enc in samples:
            known_names.append(name)
            known_encodings.append(np.array(enc))
    matched = set()
    for enc in encodings:
        dists = face_recognition.face_distance(known_encodings, enc)
        best = int(np.argmin(dists))
        if dists[best] <= float(threshold):
            matched.add(known_names[best])
    print(json.dumps({"matches": list(matched), "has_children": len(matched) > 0, "faces_found": len(encodings)}))

if __name__ == '__main__':
    if len(sys.argv) < 4:
        print(json.dumps({"error": "Insufficient arguments"})); sys.exit(1)
    mode = sys.argv[1]
    if mode == 'enroll':
        enroll(sys.argv[2], sys.argv[3], sys.argv[4])
    elif mode == 'recognize':
        recognize(sys.argv[2], sys.argv[3], float(sys.argv[4]) if len(sys.argv) > 4 else 0.5)
    else:
        print(json.dumps({"error": f"Unknown mode: {mode}"}))
