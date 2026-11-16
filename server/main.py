import base64
import hashlib
import hmac
import os
import sys
import time
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

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

from database import init_db, COVER_DIR, MUSIC_DIR, PIN_DIR, ARTIST_DIR
from routes import songs, auth, playlists, history, pins, fuse, artists, club, notifications, requests

# Ensure directories exist
os.makedirs(COVER_DIR, exist_ok=True)
os.makedirs(MUSIC_DIR, exist_ok=True)
os.makedirs(PIN_DIR, exist_ok=True)
os.makedirs(ARTIST_DIR, exist_ok=True)
init_db()

API_SECRET = os.getenv("API_SECRET", "alaktra_secure_app_secret_998877")
HMAC_SECRET = os.getenv("HMAC_SECRET", "alaktra_hmac_secret_key_334455").encode("utf-8")
MAX_TIME_DRIFT_MS = 10 * 60 * 1000  # 10 minutes tolerance
PUBLIC_REQUEST_HOSTS = {"alaktracare.in", "www.alaktracare.in"}

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


def _request_host(request: Request) -> str:
    raw = request.headers.get("host") or request.headers.get("x-forwarded-host") or ""
    return raw.split(",")[0].split(":")[0].strip().lower()


@app.middleware("http")
async def verify_app_security(request: Request, call_next):
    path = request.url.path
    req_host = _request_host(request)

    # 1. Public Song Request Portal domain (alaktracare.in / www.alaktracare.in)
    if req_host in PUBLIC_REQUEST_HOSTS:
        if request.method == "OPTIONS" or path in [
            "/", "/request", "/requests",
            "/song-requests/submit", "/song-requests/public",
            "/health", "/favicon.ico"
        ]:
            return await call_next(request)
        return JSONResponse(
            status_code=403,
            content={"detail": "Access denied on public request domain."}
        )

    # 2. Allow preflight CORS requests & public web portals/health endpoints
    if request.method == "OPTIONS" or path in [
        "/", "/request", "/requests",
        "/song-requests/submit", "/song-requests/public",
        "/upload", "/migrate-art", "/update-art",
        "/health", "/docs", "/openapi.json", "/redoc", "/favicon.ico"
    ]:
        return await call_next(request)

    # 3. Block direct web browser navigation (typing URL directly into address bar in Chrome, Safari, etc.)
    sec_fetch_mode = request.headers.get("sec-fetch-mode", "").lower()
    accept_header = request.headers.get("accept", "").lower()

    if sec_fetch_mode == "navigate" or "text/html" in accept_header or "application/xhtml+xml" in accept_header:
        return JSONResponse(
            status_code=403,
            content={"detail": "Access denied: Direct browser navigation is forbidden. Use the official Alaktra Music app."}
        )

    # 4. Verify request headers sent by Android app (X-App-Secret or App User-Agent)
    client_app_secret = request.headers.get("x-app-secret")
    user_agent = request.headers.get("user-agent", "")

    is_app_secret_valid = bool(client_app_secret and client_app_secret == API_SECRET)
    is_app_user_agent_valid = bool(user_agent and user_agent.startswith("AlaktraAndroidApp"))

    # Allow request if App Secret or App User-Agent is present, or if accessing static asset streams from app player
    if is_app_secret_valid or is_app_user_agent_valid or path.startswith(("/covers", "/music", "/pins", "/artist-media")):
        return await call_next(request)

    return JSONResponse(
        status_code=403,
        content={"detail": "Access denied: Unauthorized client request. Direct browser access is forbidden."}
    )

# Mount static asset directories
app.mount("/covers", StaticFiles(directory=COVER_DIR), name="covers")
app.mount("/music", StaticFiles(directory=MUSIC_DIR), name="music")
app.mount("/pins", StaticFiles(directory=PIN_DIR), name="pins")
app.mount("/artist-media", StaticFiles(directory=ARTIST_DIR), name="artist_media")

# Include Routers with exact prefix compatibility for the Android app
app.include_router(auth.router, prefix="/auth")
app.include_router(songs.router)
app.include_router(artists.router)
app.include_router(playlists.router)
app.include_router(history.router)
app.include_router(pins.router)
app.include_router(fuse.router)
app.include_router(club.router)
app.include_router(notifications.router)
app.include_router(requests.router)


def _serve_request_page():
    req_path = os.path.join(BASE_DIR, "request.html")
    if os.path.exists(req_path):
        return FileResponse(req_path)
    return "<h3>Song Request Page (request.html) not found</h3>"


# Public Song Request Page (served on alaktracare.in/ or /request)
@app.get("/request", response_class=HTMLResponse)
@app.get("/requests", response_class=HTMLResponse)
def serve_request_portal():
    return _serve_request_page()


# Root Portal (routes alaktracare.in -> Song Request Page, otherwise Upload Portal)
@app.get("/", response_class=HTMLResponse)
def serve_root_portal(request: Request):
    if _request_host(request) in PUBLIC_REQUEST_HOSTS:
        return _serve_request_page()
    index_path = os.path.join(BASE_DIR, "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path)
    return "<h3>Alaktra Music Player Server is Running!</h3>"


# Web Upload Portal
@app.get("/upload", response_class=HTMLResponse)
def serve_upload_portal():
    index_path = os.path.join(BASE_DIR, "index.html")
    if os.path.exists(index_path):
        return FileResponse(index_path)
    return "<h3>Alaktra Music Player Server is Running!</h3>"


# Artwork Metadata & Theme Color Migration Tool
@app.get("/migrate-art", response_class=HTMLResponse)
@app.get("/update-art", response_class=HTMLResponse)
def serve_migration_portal():
    migrate_path = os.path.join(BASE_DIR, "migrate_art.html")
    if os.path.exists(migrate_path):
        return FileResponse(migrate_path)
    return "<h3>Migration Portal (migrate_art.html) not found</h3>"


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
