from fastapi import APIRouter, UploadFile, File, Form, Query, Request, HTTPException
from fastapi.responses import StreamingResponse, Response, JSONResponse
from database import get_db, MUSIC_DIR, COVER_DIR
import uuid
import os
import hashlib
import mimetypes
import struct
import io

router = APIRouter(prefix="/songs", tags=["songs"])

def get_base_url(request: Request) -> str:
    """Returns the base URL matching the client connection or environment config."""
    env_host = os.getenv("HOST_URL")
    if env_host:
        return env_host.rstrip("/")
    if request:
        host = request.headers.get("host")
        proto = request.headers.get("x-forwarded-proto", request.url.scheme)
        if host:
            return f"{proto}://{host}"
    return "http://100.65.126.106:8000"

def calculate_duration(file_path: str) -> float:
    """Attempts to calculate track duration in seconds using mutagen if installed."""
    try:
        from mutagen import File as MutagenFile
        audio_info = MutagenFile(file_path)
        if audio_info and audio_info.info and hasattr(audio_info.info, "length"):
            return round(float(audio_info.info.length), 2)
    except Exception:
        pass
    return 0.0


def sanitize_and_inject_color_metadata(image_bytes: bytes, ext: str, color_hex: str) -> bytes:
    """
    Completely erases all existing metadata (GPS, camera tags, device info, timestamps, XMP, IPTC)
    from the cover image and embeds the user-chosen color directly into the image's EXIF metadata.
    Uses Pillow if installed, and seamlessly falls back to a pure-Python byte-level EXIF injector.
    """
    clean_hex = (color_hex or "").strip().upper()
    if not clean_hex.startswith("#"):
        clean_hex = "#" + clean_hex
    if len(clean_hex) != 7 or not all(c in "0123456789ABCDEF" for c in clean_hex[1:]):
        clean_hex = "#00E599"

    meta_payload = f"DOMINANT_COLOR:{clean_hex}"

    # 1. Attempt sanitization via Pillow if available
    try:
        from PIL import Image
        img = Image.open(io.BytesIO(image_bytes))
        out_buf = io.BytesIO()
        lower_ext = ext.lower()
        target_fmt = "JPEG" if lower_ext in (".jpg", ".jpeg") else ("PNG" if lower_ext == ".png" else (img.format or "JPEG"))

        if target_fmt == "JPEG":
            exif = img.getexif()
            exif.clear()  # Erase all prior metadata
            exif[0x010E] = meta_payload  # ImageDescription
            exif[0x0131] = f"AlaktraMusic {meta_payload}"  # Software
            exif[0x9286] = meta_payload.encode("utf-8")  # UserComment
            img.save(out_buf, format="JPEG", quality=95, exif=exif)
            return out_buf.getvalue()
        elif target_fmt == "PNG":
            from PIL.PngImagePlugin import PngInfo
            target_info = PngInfo()
            target_info.add_text("Comment", meta_payload)
            target_info.add_text("Description", meta_payload)
            target_info.add_text("Software", f"AlaktraMusic {meta_payload}")
            img.save(out_buf, format="PNG", pnginfo=target_info)
            return out_buf.getvalue()
    except Exception:
        pass

    # 2. Pure-Python Zero-Dependency JPEG Sanitizer & EXIF APP1 Injector
    if image_bytes.startswith(b"\xFF\xD8"):
        try:
            # Strip all metadata markers: APP1-APP15 (0xE1..0xEF) and COM (0xFE)
            strip_markers = {0xE1, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xEB, 0xEC, 0xED, 0xEE, 0xEF, 0xFE}

            # Build minimal TIFF with ImageDescription (0x010E) and Software (0x0131)
            ascii_bytes = meta_payload.encode("utf-8") + b"\x00"
            str_len = len(ascii_bytes)
            offset_str1 = 38
            offset_str2 = 38 + str_len
            entry1 = struct.pack("<HHII", 0x010E, 2, str_len, offset_str1)
            entry2 = struct.pack("<HHII", 0x0131, 2, str_len, offset_str2)
            ifd0 = struct.pack("<H", 2) + entry1 + entry2 + struct.pack("<I", 0)
            tiff = b"II\x2a\x00" + struct.pack("<I", 8) + ifd0 + ascii_bytes + ascii_bytes
            app1_payload = b"Exif\x00\x00" + tiff
            app1_seg = b"\xFF\xE1" + struct.pack(">H", len(app1_payload) + 2) + app1_payload

            out = [b"\xFF\xD8", app1_seg]
            i = 2
            while i < len(image_bytes) - 1:
                if image_bytes[i] != 0xFF:
                    out.append(image_bytes[i:])
                    break
                marker = image_bytes[i + 1]
                i += 2
                if marker in (0xD8, 0xD9):
                    out.append(bytes([0xFF, marker]))
                    continue
                if marker == 0xDA:  # SOS (Start of Scan - image data starts)
                    out.append(bytes([0xFF, marker]))
                    out.append(image_bytes[i:])
                    break
                if i + 2 > len(image_bytes):
                    break
                seg_len = struct.unpack(">H", image_bytes[i:i + 2])[0]
                if marker not in strip_markers:
                    out.append(bytes([0xFF, marker]))
                    out.append(image_bytes[i:i + seg_len])
                i += seg_len
            return b"".join(out)
        except Exception:
            return image_bytes

    return image_bytes


# 🎵 GET ALL SONGS
@router.get("")
def get_songs(request: Request):
    db = get_db()
    songs = db.execute("SELECT * FROM songs ORDER BY id DESC").fetchall()
    base_url = get_base_url(request)

    latest_token = f"{len(songs)}_{songs[0]['id'] if songs else 0}"
    etag = f'"{hashlib.md5(latest_token.encode()).hexdigest()}"'
    client_etag = request.headers.get("if-none-match")
    if client_etag and client_etag.strip('"') == etag.strip('"'):
        return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})

    data = [
        {
            "id": s["id"],
            "title": s["title"],
            "artist": s["artist"],
            "audio_url": f"{base_url}/songs/{s['id']}/stream",
            "cover_url": f"{base_url}/{s['cover_path'].replace(os.sep, '/')}",
            "dominant_color": s["dominant_color"] or "#00E599",
            "duration": float(s["duration"]) if s["duration"] else 0.0,
        }
        for s in songs
    ]
    return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})


# 🎵 STREAM SONG AUDIO (Full Range Support for Android ExoPlayer)
@router.api_route(
    "/{song_id}/stream",
    methods=["GET", "HEAD"]
)
def stream_song(song_id: int, request: Request):
    db = get_db()
    song = db.execute(
        "SELECT * FROM songs WHERE id = ?",
        (song_id,),
    ).fetchone()

    if not song:
        raise HTTPException(status_code=404, detail="Song not found")

    file_path = song["audio_path"]
    if not os.path.isabs(file_path):
        file_path = os.path.join(os.path.dirname(os.path.dirname(__file__)), file_path)

    if not os.path.exists(file_path):
        raise HTTPException(status_code=404, detail="Audio file not found on disk")

    file_size = os.path.getsize(file_path)
    etag = song["etag"]

    if not etag:
        with open(file_path, "rb") as f:
            file_hash = hashlib.md5(f.read()).hexdigest()
        db.execute("UPDATE songs SET etag=? WHERE id=?", (file_hash, song_id))
        db.commit()
        etag = file_hash

    content_type, _ = mimetypes.guess_type(file_path)
    if not content_type:
        content_type = "audio/mpeg"

    range_header = request.headers.get("range") or request.headers.get("Range")

    if request.method == "HEAD":
        return StreamingResponse(
            iter([]),
            status_code=200,
            headers={
                "Accept-Ranges": "bytes",
                "Content-Length": str(file_size),
                "Content-Type": content_type,
                "ETag": etag,
            }
        )

    def iterfile(start: int, end: int, chunk_size: int = 16384):
        with open(file_path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                read_bytes = min(chunk_size, remaining)
                data = f.read(read_bytes)
                if not data:
                    break
                remaining -= len(data)
                yield data

    if range_header and range_header.startswith("bytes="):
        try:
            bytes_range = range_header.replace("bytes=", "").strip()
            parts = bytes_range.split("-")
            start_str, end_str = parts[0].strip(), parts[1].strip()

            if start_str and int(start_str) >= file_size:
                return StreamingResponse(
                    iter([]),
                    status_code=416,
                    headers={
                        "Content-Range": f"bytes */{file_size}",
                        "Accept-Ranges": "bytes",
                    }
                )

            if start_str and end_str:
                start = int(start_str)
                end = min(int(end_str), file_size - 1)
            elif start_str:
                start = int(start_str)
                end = file_size - 1
            elif end_str:
                # Suffix byte range
                length = int(end_str)
                start = max(0, file_size - length)
                end = file_size - 1
            else:
                start = 0
                end = file_size - 1

            start = max(0, min(start, file_size - 1))
            end = max(start, min(end, file_size - 1))
            chunk_length = end - start + 1

            headers = {
                "Content-Range": f"bytes {start}-{end}/{file_size}",
                "Accept-Ranges": "bytes",
                "Content-Length": str(chunk_length),
                "Content-Type": content_type,
                "ETag": etag,
                "Last-Modified": str(song["updated_at"] or ""),
            }

            return StreamingResponse(
                iterfile(start, end),
                status_code=206,
                headers=headers,
            )
        except Exception as e:
            # Fallback to full stream if range parsing encounters edge case
            pass

    # Full file response fallback
    headers = {
        "Accept-Ranges": "bytes",
        "Content-Length": str(file_size),
        "Content-Type": content_type,
        "ETag": etag,
    }
    return StreamingResponse(
        iterfile(0, file_size - 1) if file_size > 0 else iter([]),
        media_type=content_type,
        headers=headers,
    )


# 🎵 UPLOAD SONG
@router.post("/upload")
async def upload_song(
    title: str = Form(...),
    artist: str = Form(...),
    audio: UploadFile = File(...),
    cover: UploadFile = File(...),
    color: str = Form("#00E599")
):
    # Determine safe extensions
    audio_ext = os.path.splitext(audio.filename or "")[1].lower() or ".mp3"
    cover_ext = os.path.splitext(cover.filename or "")[1].lower() or ".jpg"

    audio_filename = f"{uuid.uuid4()}{audio_ext}"
    cover_filename = f"{uuid.uuid4()}{cover_ext}"

    audio_path = os.path.join(MUSIC_DIR, audio_filename)
    cover_path = os.path.join(COVER_DIR, cover_filename)

    audio_bytes = await audio.read()
    raw_cover_bytes = await cover.read()

    # Format and sanitize color hex
    clean_color = (color or "").strip().upper()
    if not clean_color.startswith("#"):
        clean_color = "#" + clean_color
    if len(clean_color) != 7 or not all(c in "0123456789ABCDEF" for c in clean_color[1:]):
        clean_color = "#00E599"

    # Erase all prior image metadata and embed the chosen theme color into the image's EXIF metadata
    sanitized_cover_bytes = sanitize_and_inject_color_metadata(raw_cover_bytes, cover_ext, clean_color)

    with open(audio_path, "wb") as f:
        f.write(audio_bytes)

    with open(cover_path, "wb") as f:
        f.write(sanitized_cover_bytes)

    # Relative paths for database & web serving
    rel_audio_path = os.path.join("music", audio_filename).replace(os.sep, "/")
    rel_cover_path = os.path.join("covers", cover_filename).replace(os.sep, "/")

    etag = hashlib.md5(audio_bytes).hexdigest()
    duration = calculate_duration(audio_path)

    db = get_db()
    cursor = db.execute(
        """
        INSERT INTO songs (title, artist, audio_path, cover_path, dominant_color, etag, duration, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))
        """,
        (title.strip(), artist.strip(), rel_audio_path, rel_cover_path, clean_color, etag, duration),
    )
    db.commit()
    new_song_id = cursor.lastrowid

    return {
        "success": True,
        "id": new_song_id,
        "title": title.strip(),
        "artist": artist.strip(),
        "dominant_color": clean_color
    }


# 🔍 SEARCH SONGS
@router.get("/search")
def search_songs(
    request: Request,
    q: str = Query("", description="Search query"),
    limit: int = Query(30, ge=1, le=100),
    offset: int = Query(0, ge=0),
):
    db = get_db()
    clean_q = q.strip()
    query = f"%{clean_q}%"

    songs = db.execute(
        """
        SELECT *
        FROM songs
        WHERE title LIKE ? OR artist LIKE ?
        ORDER BY id DESC
        LIMIT ? OFFSET ?
        """,
        (query, query, limit, offset),
    ).fetchall()

    base_url = get_base_url(request)

    query_sig = f"{clean_q}_{limit}_{offset}_{len(songs)}_{songs[0]['id'] if songs else 0}"
    etag = f'"{hashlib.md5(query_sig.encode()).hexdigest()}"'
    client_etag = request.headers.get("if-none-match")
    if client_etag and client_etag.strip('"') == etag.strip('"'):
        return Response(status_code=304, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})

    data = [
        {
            "id": s["id"],
            "title": s["title"],
            "artist": s["artist"],
            "audio_url": f"{base_url}/songs/{s['id']}/stream",
            "cover_url": f"{base_url}/{s['cover_path'].replace(os.sep, '/')}",
            "dominant_color": s["dominant_color"] or "#00E599",
            "duration": float(s["duration"]) if s["duration"] else 0.0,
        }
        for s in songs
    ]
    return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})


# 🎨 UPDATE EXISTING SONG COVER METADATA & THEME COLOR
@router.post("/{song_id}/update-cover-metadata")
def update_song_cover_metadata(song_id: int, color: str = Form(...)):
    db = get_db()
    song = db.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
    if not song:
        raise HTTPException(status_code=404, detail="Song not found")

    clean_color = (color or "").strip().upper()
    if not clean_color.startswith("#"):
        clean_color = "#" + clean_color
    if len(clean_color) != 7 or not all(c in "0123456789ABCDEF" for c in clean_color[1:]):
        clean_color = "#00E599"

    cover_rel = song["cover_path"]
    cover_abs = os.path.join(os.path.dirname(os.path.dirname(__file__)), cover_rel)
    if os.path.exists(cover_abs):
        ext = os.path.splitext(cover_abs)[1].lower() or ".jpg"
        try:
            with open(cover_abs, "rb") as f:
                raw_bytes = f.read()
            sanitized_bytes = sanitize_and_inject_color_metadata(raw_bytes, ext, clean_color)
            with open(cover_abs, "wb") as f:
                f.write(sanitized_bytes)
        except Exception as e:
            print(f"⚠️ Error updating cover image file on disk: {e}")

    db.execute(
        "UPDATE songs SET dominant_color = ?, updated_at = datetime('now') WHERE id = ?",
        (clean_color, song_id)
    )
    db.commit()

    return {
        "success": True,
        "id": song_id,
        "title": song["title"],
        "dominant_color": clean_color
    }


# 🎨 BATCH UPDATE COVER METADATA FOR ALL EXISTING SONGS
@router.post("/batch-update-cover-metadata")
async def batch_update_cover_metadata(request: Request):
    """Batch updates metadata and theme colors for multiple existing songs at once."""
    payload = await request.json()
    items = payload if isinstance(payload, list) else payload.get("items", [])
    db = get_db()
    results = []

    for item in items:
        song_id = item.get("song_id") or item.get("id")
        color = item.get("color", "#00E599")
        if not song_id:
            continue

        song = db.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
        if not song:
            continue

        clean_color = (color or "").strip().upper()
        if not clean_color.startswith("#"):
            clean_color = "#" + clean_color
        if len(clean_color) != 7 or not all(c in "0123456789ABCDEF" for c in clean_color[1:]):
            clean_color = "#00E599"

        cover_rel = song["cover_path"]
        cover_abs = os.path.join(os.path.dirname(os.path.dirname(__file__)), cover_rel)
        if os.path.exists(cover_abs):
            ext = os.path.splitext(cover_abs)[1].lower() or ".jpg"
            try:
                with open(cover_abs, "rb") as f:
                    raw_bytes = f.read()
                sanitized_bytes = sanitize_and_inject_color_metadata(raw_bytes, ext, clean_color)
                with open(cover_abs, "wb") as f:
                    f.write(sanitized_bytes)
            except Exception as e:
                print(f"⚠️ Error updating cover image file for song {song_id}: {e}")

        db.execute(
            "UPDATE songs SET dominant_color = ?, updated_at = datetime('now') WHERE id = ?",
            (clean_color, song_id)
        )
        results.append({"id": song_id, "title": song["title"], "dominant_color": clean_color})

    db.commit()
    return {"success": True, "updated_count": len(results), "results": results}

