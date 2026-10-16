from fastapi import APIRouter, UploadFile, File, Form, Query, Request, HTTPException
from fastapi.responses import JSONResponse
from database import get_db
import os
import uuid
import math
from datetime import datetime

router = APIRouter(prefix="/pins", tags=["pins"])

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PIN_DIR = os.path.join(BASE_DIR, "pins")
os.makedirs(PIN_DIR, exist_ok=True)

def get_base_url(request: Request) -> str:
    env_host = os.getenv("HOST_URL")
    if env_host:
        return env_host.rstrip("/")
    if request:
        host = request.headers.get("host")
        proto = request.headers.get("x-forwarded-proto", request.url.scheme)
        if host:
            return f"{proto}://{host}"
    return "http://100.65.126.106:8000"

def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculates great-circle distance between two points in meters."""
    R = 6371000  # Earth radius in meters
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c

def cleanup_expired_pins(conn):
    """Removes pins from past months (pins are only valid for the current month)."""
    now = datetime.utcnow()
    current_year = now.year
    current_month = now.month
    conn.execute(
        "DELETE FROM location_pins WHERE year < ? OR (year = ? AND month < ?)",
        (current_year, current_year, current_month)
    )
    conn.commit()

@router.post("")
async def create_pin(
    request: Request,
    user_id: int = Form(...),
    song_id: int = Form(...),
    latitude: float = Form(...),
    longitude: float = Form(...),
    image: UploadFile = File(...)
):
    conn = get_db()
    cursor = conn.cursor()

    # Verify song exists
    song_row = cursor.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
    if not song_row:
        conn.close()
        raise HTTPException(status_code=404, detail="Song not found")

    # Save image
    ext = os.path.splitext(image.filename)[1] if image.filename else ".jpg"
    if not ext:
        ext = ".jpg"
    filename = f"{uuid.uuid4()}{ext}"
    file_path = os.path.join(PIN_DIR, filename)

    contents = await image.read()
    with open(file_path, "wb") as f:
        f.write(contents)

    now = datetime.utcnow()
    year = now.year
    month = now.month

    # Cleanup expired pins first
    cleanup_expired_pins(conn)

    cursor.execute("""
        INSERT INTO location_pins (user_id, song_id, latitude, longitude, image_path, year, month)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    """, (user_id, song_id, latitude, longitude, filename, year, month))
    conn.commit()
    pin_id = cursor.lastrowid
    conn.close()

    base_url = get_base_url(request)
    image_url = f"{base_url}/pins/{filename}"

    return {
        "id": pin_id,
        "user_id": user_id,
        "song_id": song_id,
        "latitude": latitude,
        "longitude": longitude,
        "image_url": image_url,
        "created_at": now.isoformat()
    }

@router.get("")
def get_nearby_pins(
    request: Request,
    lat: float = Query(..., description="User latitude"),
    lng: float = Query(..., description="User longitude")
):
    conn = get_db()
    cursor = conn.cursor()

    # Automatically remove expired pins from previous months
    cleanup_expired_pins(conn)

    # Fetch all active pins for current month
    rows = cursor.execute("""
        SELECT p.*, s.title, s.artist, s.audio_path, s.cover_path, s.dominant_color, s.duration
        FROM location_pins p
        JOIN songs s ON p.song_id = s.id
    """).fetchall()
    conn.close()

    base_url = get_base_url(request)
    nearby_pins = []

    for row in rows:
        pin_lat = row["latitude"]
        pin_lng = row["longitude"]
        dist = haversine_distance(lat, lng, pin_lat, pin_lng)

        # Check if within 50 meters range
        if dist <= 50.0:
            audio_url = f"{base_url}/songs/{row['song_id']}/stream"
            cover_path = row["cover_path"] or ""
            cover_url = f"{base_url}/{cover_path.replace(os.sep, '/')}"
            pin_image_url = f"{base_url}/pins/{row['image_path']}"

            nearby_pins.append({
                "id": row["id"],
                "user_id": row["user_id"],
                "song_id": row["song_id"],
                "latitude": pin_lat,
                "longitude": pin_lng,
                "distance_meters": round(dist, 1),
                "image_url": pin_image_url,
                "created_at": row["created_at"],
                "song": {
                    "id": row["song_id"],
                    "title": row["title"],
                    "artist": row["artist"],
                    "audio_url": audio_url,
                    "cover_url": cover_url,
                    "audioUrl": audio_url,
                    "coverUrl": cover_url,
                    "duration": float(row["duration"]) if row["duration"] else 0.0,
                    "dominant_color": row["dominant_color"] or "#00E599"
                }
            })

    # Sort by nearest distance
    nearby_pins.sort(key=lambda x: x["distance_meters"])
    return nearby_pins
