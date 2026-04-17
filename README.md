# WhatsApp Monitor — Local AI Summary & Children's Photo Filter

A three-component system: **Android App** ↔ **Local Node.js Server** ↔ **LM Studio (LLM)**

```
┌─────────────────────────────────────────────────────────────────┐
│                         YOUR ANDROID PHONE                       │
│                                                                   │
│  ┌──────────────────────┐    ┌───────────────────────────────┐   │
│  │ NotificationListener │    │    MediaWatcherService         │   │
│  │ Service              │    │  Watches WhatsApp Images/      │   │
│  │                      │    │  folder for new received pics  │   │
│  │ Captures message     │    └────────────┬──────────────────┘   │
│  │ text from WA notifs  │                 │                       │
│  └──────────┬───────────┘                 │                       │
│             │ POST /api/messages           │ POST /api/images      │
│             │                             │                       │
└─────────────┼─────────────────────────────┼───────────────────────┘
              │          LOCAL WIFI          │
┌─────────────▼─────────────────────────────▼───────────────────────┐
│                      NODE.JS SERVER  :3000                         │
│                                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐ │
│  │  REST API    │  │  WebSocket   │  │   SQLite Database         │ │
│  │  (Express)   │  │  Server      │  │   - conversations         │ │
│  │              │  │              │  │   - messages              │ │
│  │  /messages   │  │  Broadcasts  │  │   - summaries             │ │
│  │  /images     │  │  summary_    │  │   - images (w/ face tags) │ │
│  │  /faces      │  │  updated     │  │   - enrolled_faces        │ │
│  └──────┬───────┘  └──────▲───────┘  └──────────────────────────┘ │
│         │                  │                                        │
│  ┌──────▼─────────┐  ┌────┴────────────────────┐                  │
│  │  LLM Service   │  │  Face Recognition        │                  │
│  │  (LM Studio)   │  │  (Python face_recognition│                  │
│  │                │  │   called as subprocess)  │                  │
│  │  Generates:    │  │                          │                  │
│  │  - summary     │  │  Enrollment: stores child│                  │
│  │  - intent      │  │  face embeddings in JSON │                  │
│  │  - sentiment   │  │  Matching: compares new  │                  │
│  └────────────────┘  │  photos vs enrolled faces│                  │
│                      └──────────────────────────┘                  │
└──────────────────────────────────────────────────────────────────┘
              │          LOCAL WIFI (WebSocket push)
┌─────────────▼──────────────────────────────────────────────────────┐
│                         ANDROID APP (UI)                            │
│                                                                     │
│  ┌──────────────────────────────┐  ┌──────────────────────────┐    │
│  │   Conversations Tab          │  │   Children's Photos Tab  │    │
│  │                              │  │                          │    │
│  │  ┌────────────────────────┐  │  │  Grid of thumbnails from │    │
│  │  │ 📱 Mom                 │  │  │  WhatsApp images where   │    │
│  │  │ Summary: Asking about  │  │  │  enrolled children were  │    │
│  │  │ dinner plans tonight   │  │  │  detected by face        │    │
│  │  │ Intent: Coordinating   │  │  │  recognition             │    │
│  │  │ Feeling: Friendly      │  │  │                          │    │
│  │  └────────────────────────┘  │  │  Tap → full image on     │    │
│  │  ┌────────────────────────┐  │  │  server                  │    │
│  │  │ 👥 Work Group          │  │  └──────────────────────────┘    │
│  │  │ Summary: Sprint review │  │                                   │
│  │  │ tomorrow, need slides  │  │                                   │
│  │  │ Intent: Action request │  │                                   │
│  │  │ Feeling: Urgent        │  │                                   │
│  │  └────────────────────────┘  │                                   │
│  └──────────────────────────────┘                                   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

### 1. Prerequisites
- **Node.js 18+** on your PC
- **Python 3.8+** with `pip install face_recognition numpy` (needs CMake + dlib)
- **LM Studio** running with a model loaded and the local server enabled
- **Android Studio** (Flamingo or newer) to build the Android app
- Phone and PC on the **same WiFi** network

### 2. Server Setup
```bash
cd node-server
cp .env.example .env
# Edit .env — set AUTH_TOKEN to a random string, verify LM_STUDIO_BASE_URL
npm install
npm start
```
The server starts on `http://0.0.0.0:3000`. Note your PC's local IP (e.g. 192.168.1.50).

### 3. LM Studio Setup
1. Open LM Studio and load a model (Mistral 7B, Llama 3 8B, or similar)
2. Click **"Start Server"** — it starts on port 1234 by default
3. In `.env`, verify: `LM_STUDIO_BASE_URL=http://127.0.0.1:1234/v1`

### 4. Android App Setup
1. Open `android-app/` in Android Studio
2. Set your PC's IP in `ApiClient.kt` → `serverUrl` (or use Settings screen)
3. Set the same `AUTH_TOKEN` from `.env`
4. Build & install on your phone
5. **Grant Notification Access**: Settings → Notifications → Notification Access → enable WA Monitor
6. **Grant Storage Permission** when prompted (for image watching)

### 5. Enroll Your Children's Faces
Either via the Android app (Face Enrollment screen) or directly via curl:
```bash
curl -X POST http://192.168.1.50:3000/api/faces/enroll \
  -H "X-Auth-Token: your-token" \
  -F "childName=Avi" \
  -F "photo=@/path/to/avi_photo.jpg"
# Repeat with 3-5 different photos per child for better accuracy
```

---

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/messages` | Ingest a WhatsApp message |
| GET | `/api/conversations` | List all conversations + summaries |
| GET | `/api/messages/:convId` | Message history for a conversation |
| POST | `/api/images` | Upload an image for face recognition |
| GET | `/api/images/children` | Get all images tagged with children |
| POST | `/api/faces/enroll` | Enroll a child's face |
| GET | `/api/faces` | List enrolled children |
| DELETE | `/api/faces/:name` | Remove a child's enrollment |
| GET | `/api/health` | Health check |

### WebSocket Events (server → app)
```json
{
  "type": "summary_updated",
  "conversationId": "com.whatsapp_mom",
  "contactName": "Mom",
  "summary": "Asking about dinner plans tonight",
  "intent": "Coordinating family plans",
  "sentiment": "friendly",
  "timestamp": 1713000000000
}
```

### Auth
All requests require the header: `X-Auth-Token: your-secret-token`
WebSocket connection requires query param: `ws://host:3000?token=your-secret-token`

---

## Project Structure

```
whatsapp-monitor-project/
├── README.md
├── wam-server/
│   ├── server.js          ← Express + WebSocket entrypoint
│   ├── db.js              ← SQLite schema + connection
│   ├── ws-handler.js      ← WebSocket client management
│   ├── services/
│   │   ├── llm.js         ← LM Studio API (OpenAI-compatible)
│   │   ├── face.py        ← Python face recognition script
│   │   └── face.js        ← Node.js wrapper (spawns face.py)
│   ├── routes/
│   │   ├── messages.js    ← Message ingestion + LLM trigger
│   │   ├── images.js      ← Image upload + face scan + thumbnails
│   │   └── faces.js       ← Child face enrollment management
│   ├── data/              ← SQLite DB + face_db.json (auto-created)
│   ├── uploads/           ← Stored images + thumbnails (auto-created)
│   ├── .env.example
│   └── package.json
└── wam-app/
    ├── build.gradle
    ├── AndroidManifest.xml
    └── src/
        ├── api/
        │   ├── ApiClient.kt       ← Retrofit + OkHttp WebSocket client
        │   └── ApiService.kt      ← REST interface definition
        ├── models/
        │   └── Models.kt          ← Data classes
        ├── services/
        │   ├── WhatsAppListenerService.kt ← NotificationListenerService
        │   └── MediaWatcherService.kt     ← FileObserver for WA Images
        └── ui/
            ├── ConversationsActivity.kt   ← Main screen
            ├── SettingsActivity.kt        ← Server URL + token config
            ├── FaceEnrollmentActivity.kt  ← Enroll children's faces
            └── ChildrenPhotosActivity.kt  ← Browse matched photos
```

---

## Face Recognition Notes

- Uses Python's `face_recognition` library (dlib-based HOG + CNN detector)
- **Enrollment**: send 3–5 clear photos of each child's face from different angles
- **Threshold**: 0.5 (default) — lower = stricter. Adjust via `FACE_MATCH_THRESHOLD` in `.env`
- **Install dlib on Windows**: `pip install cmake dlib face_recognition`
  (may need Visual Studio Build Tools — see https://github.com/ageitgey/face_recognition)

---

## Privacy & Security

- All data stays **100% local** — nothing leaves your home network
- Auth token prevents unauthorized access to the server
- Messages and images stored in `node-server/data/` and `node-server/uploads/`
- To wipe all data: delete `data/whatsapp_mirror.db`, `data/face_db.json`, and `uploads/`

---

## Known Limitations

1. **WhatsApp message capture depends on notifications** — if WhatsApp is open and focused, Android may suppress the notification and the message won't be captured
2. **Bundled notifications**: WhatsApp groups multiple messages into one notification on some devices; the listener handles this but some messages may be missed if the notification was never posted
3. **Image media path** varies by Android version and WhatsApp version — see `MediaWatcherService.kt` for path constants and adjust if needed
4. **Face recognition accuracy** degrades with very young children (features change rapidly), low-light photos, or heavily filtered images
