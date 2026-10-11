from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, HTMLResponse
import os
import sys

# Ensure root server directory is in sys.path
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
if BASE_DIR not in sys.path:
    sys.path.insert(0, BASE_DIR)

# Automatically load server/.env if present
env_file = os.path.join(BASE_DIR, ".env")
if os.path.exists(env_file):
    try:
        with open(env_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
    except Exception as e:
        print(f"⚠️ Could not load .env file: {e}")

from database import init_db, COVER_DIR, MUSIC_DIR
from routes import songs, auth, playlists, history

# Ensure directories exist
os.makedirs(COVER_DIR, exist_ok=True)
os.makedirs(MUSIC_DIR, exist_ok=True)
init_db()

app = FastAPI(
    title="Alaktra Music Server",
    description="Backend audio streaming, authentication, and playlist management API for Android Music Player",
    version="1.0.0"
)

# CORS Middleware for Android app, web portal, and emulator requests
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Mount static asset directories
app.mount("/covers", StaticFiles(directory=COVER_DIR), name="covers")
app.mount("/music", StaticFiles(directory=MUSIC_DIR), name="music")

# Include Routers with exact prefix compatibility for the Android app
app.include_router(auth.router, prefix="/auth")
app.include_router(songs.router)
app.include_router(playlists.router)
app.include_router(history.router)


# Web Upload Portal
@app.get("/", response_class=HTMLResponse)
@app.get("/upload", response_class=HTMLResponse)
def serve_upload_portal():
    index_path = os.path.join(BASE_DIR, "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path)
    return "<h3>Alaktra Music Player Server is Running!</h3>"


# Health Check
@app.get("/health")
def health_check():
    return {
        "status": "online",
        "service": "Alaktra Music Player Server",
        "version": "1.0.0"
    }


if __name__ == "__main__":
    import uvicorn
    port = int(os.getenv("PORT", 8000))
    host = os.getenv("HOST", "0.0.0.0")
    print(f"🎵 Starting Alaktra Music Player Server on {host}:{port}...")
    uvicorn.run("main:app", host=host, port=port, reload=True)
