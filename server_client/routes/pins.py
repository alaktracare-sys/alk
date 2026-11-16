from fastapi import APIRouter, UploadFile, File, Form, Query, Request, HTTPException
from database import get_db, attach_artists_to_song_dicts
import os
import io
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
    return "https://api.alaktracare.in"


def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Calculates great-circle distance between two points in meters."""
    R = 6371000  # Earth radius in meters
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_phi = math.radians(lat2 - lat1)
    d_lambda = math.radians(lon2 - lon1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
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


def _save_sanitized_pin_image(raw_bytes: bytes, ext: str, file_path: str):
    """Strips GPS/camera EXIF metadata while preserving upright EXIF orientation."""
    try:
        from PIL import Image, ImageOps
        img = ImageOps.exif_transpose(Image.open(io.BytesIO(raw_bytes)))
        buf = io.BytesIO()
        lower_ext = ext.lower()
        if lower_ext == ".png":
            img.save(buf, format="PNG")
        elif lower_ext == ".webp":
            img.save(buf, format="WEBP", quality=90)
        else:
            if img.mode not in ("RGB", "L"):
                img = img.convert("RGB")
            img.save(buf, format="JPEG", quality=90)
        with open(file_path, "wb") as f:
            f.write(buf.getvalue())
        return
    except Exception:
        pass

    with open(file_path, "wb") as f:
        f.write(raw_bytes)


@router.post("")
async def create_pin(
    request: Request,
    user_id: int = Form(...),
    song_id: int = Form(...),
    latitude: float = Form(...),
    longitude: float = Form(...),
    image: UploadFile = File(...)
):
    raw_ext = os.path.splitext(image.filename or "")[1].lower()
    ext = raw_ext if raw_ext in (".jpg", ".jpeg", ".png", ".webp") else ".jpg"
    filename = f"{uuid.uuid4()}{ext}"
    file_path = os.path.join(PIN_DIR, filename)

    conn = get_db()
    try:
        cursor = conn.cursor()

        # Verify song exists
        song_row = cursor.execute("SELECT id FROM songs WHERE id = ?", (song_id,)).fetchone()
        if not song_row:
            raise HTTPException(status_code=404, detail="Song not found")

        contents = await image.read()
        if not contents:
            raise HTTPException(status_code=400, detail="Uploaded pin image is empty")

        _save_sanitized_pin_image(contents, ext, file_path)

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
    finally:
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
    try:
        cursor = conn.cursor()

        # Automatically remove expired pins from previous months
        cleanup_expired_pins(conn)

        # Fetch all active pins for current month
        rows = cursor.execute("""
            SELECT p.*, s.title, s.artist, s.audio_path, s.cover_path, s.dominant_color, s.duration
            FROM location_pins p
            JOIN songs s ON p.song_id = s.id
        """).fetchall()

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

        if nearby_pins:
            attach_artists_to_song_dicts(conn, [p["song"] for p in nearby_pins], base_url)
    finally:
        conn.close()

    # Sort by nearest distance
    nearby_pins.sort(key=lambda x: x["distance_meters"])
    return nearby_pins
