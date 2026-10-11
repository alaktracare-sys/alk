from fastapi import APIRouter, UploadFile, File, Form, Query, Request, HTTPException
from fastapi.responses import StreamingResponse, Response, JSONResponse
from database import get_db, MUSIC_DIR, COVER_DIR
import uuid
import os
import hashlib
import mimetypes

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
    cover: UploadFile = File(...)
):
    # Determine safe extensions
    audio_ext = os.path.splitext(audio.filename or "")[1].lower() or ".mp3"
    cover_ext = os.path.splitext(cover.filename or "")[1].lower() or ".jpg"

    audio_filename = f"{uuid.uuid4()}{audio_ext}"
    cover_filename = f"{uuid.uuid4()}{cover_ext}"

    audio_path = os.path.join(MUSIC_DIR, audio_filename)
    cover_path = os.path.join(COVER_DIR, cover_filename)

    audio_bytes = await audio.read()
    cover_bytes = await cover.read()

    with open(audio_path, "wb") as f:
        f.write(audio_bytes)

    with open(cover_path, "wb") as f:
        f.write(cover_bytes)

    # Relative paths for database & web serving
    rel_audio_path = os.path.join("music", audio_filename).replace(os.sep, "/")
    rel_cover_path = os.path.join("covers", cover_filename).replace(os.sep, "/")

    etag = hashlib.md5(audio_bytes).hexdigest()
    duration = calculate_duration(audio_path)

    db = get_db()
    cursor = db.execute(
        """
        INSERT INTO songs (title, artist, audio_path, cover_path, etag, duration, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, datetime('now'))
        """,
        (title.strip(), artist.strip(), rel_audio_path, rel_cover_path, etag, duration),
    )
    db.commit()
    new_song_id = cursor.lastrowid

    return {
        "success": True,
        "id": new_song_id,
        "title": title.strip(),
        "artist": artist.strip()
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
            "duration": float(s["duration"]) if s["duration"] else 0.0,
        }
        for s in songs
    ]
    return JSONResponse(content=data, headers={"ETag": etag, "Cache-Control": "private, max-age=60"})

