from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional
from database import get_db

router = APIRouter(prefix="/notifications", tags=["notifications"])

class RegisterTokenRequest(BaseModel):
    user_id: Optional[int] = None
    fcm_token: str
    device_info: Optional[str] = ""

@router.post("/register-token")
def register_device_token(data: RegisterTokenRequest):
    if not data.fcm_token or not data.fcm_token.strip():
        raise HTTPException(status_code=400, detail="Invalid FCM token")
    
    token = data.fcm_token.strip()
    conn = get_db()
    try:
        cursor = conn.cursor()
        cursor.execute("""
            INSERT INTO user_device_tokens (user_id, fcm_token, device_info, updated_at)
            VALUES (?, ?, ?, datetime('now'))
            ON CONFLICT(fcm_token) DO UPDATE SET
                user_id = COALESCE(EXCLUDED.user_id, user_device_tokens.user_id),
                device_info = EXCLUDED.device_info,
                updated_at = datetime('now')
        """, (data.user_id, token, data.device_info or ""))
        conn.commit()
        return {"status": "success", "message": "Device token registered"}
    finally:
        conn.close()

@router.get("/history")
def get_notification_history(user_id: Optional[int] = None):
    conn = get_db()
    try:
        cursor = conn.cursor()
        if user_id is not None:
            cursor.execute("""
                SELECT id, title, message, recipient_type, target_user_ids, target_usernames, sent_count, created_at
                FROM sent_notifications
                WHERE recipient_type = 'all'
                   OR target_user_ids LIKE ?
                   OR target_user_ids = ?
                ORDER BY id DESC
                LIMIT 50
            """, (f"%,{user_id},%", str(user_id)))
        else:
            cursor.execute("""
                SELECT id, title, message, recipient_type, target_user_ids, target_usernames, sent_count, created_at
                FROM sent_notifications
                ORDER BY id DESC
                LIMIT 50
            """)
        rows = cursor.fetchall()
        return [dict(r) for r in rows]
    finally:
        conn.close()
