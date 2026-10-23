from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, EmailStr
from datetime import datetime, timedelta
import random
import hashlib
import secrets
import smtplib
import os
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
try:
    import bcrypt
    HAS_BCRYPT = True
except ImportError:
    HAS_BCRYPT = False

from database import get_db

router = APIRouter(tags=["auth"])

# ---------------- GMAIL SMTP CONFIGURATION ----------------
# Recommended: Provide via environment variables (e.g. GMAIL_APP_PASSWORD)
# Note: Google requires a 16-character App Password, NOT your regular Google password.
GMAIL_USER = os.getenv("GMAIL_USER", "alaktracare@gmail.com")
GMAIL_APP_PASSWORD = os.getenv("GMAIL_APP_PASSWORD", "")

def send_otp_email(recipient_email: str, otp_code: str) -> bool:
    """Sends a verification email containing the OTP code using Gmail SMTP."""
    user = os.getenv("GMAIL_USER", GMAIL_USER)
    password = os.getenv("GMAIL_APP_PASSWORD", GMAIL_APP_PASSWORD).strip().replace(" ", "")

    if not user or not password:
        print(f"\n⚠️ [SMTP NOT CONFIGURED] GMAIL_APP_PASSWORD is not set.")
        print(f"👉 To enable real email delivery to {recipient_email}, set GMAIL_APP_PASSWORD.")
        print(f"🔑 [FALLBACK CONSOLE OTP] Verification OTP for {recipient_email}: {otp_code}\n")
        return False

    try:
        msg = MIMEMultipart("alternative")
        msg["Subject"] = f"Your Alaktra Verification Code: {otp_code}"
        msg["From"] = f"Alaktra Music <{user}>"
        msg["To"] = recipient_email

        plain_text = f"Your Alaktra verification code is: {otp_code}\n\nThis code will expire in 15 minutes."
        html_content = f"""<!DOCTYPE html>
<html>
<head><meta charset="utf-8"></head>
<body style="margin: 0; padding: 24px; background-color: #0D0E15; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;">
  <table width="100%" border="0" cellspacing="0" cellpadding="0">
    <tr>
      <td align="center">
        <table width="480" border="0" cellspacing="0" cellpadding="0" style="background-color: #161822; border-radius: 16px; border: 1px solid #232738; padding: 36px 28px; text-align: center;">
          <tr>
            <td>
              <h1 style="color: #00F5A0; font-size: 28px; margin: 0 0 6px 0; letter-spacing: 2px;">ALAKTRA</h1>
              <p style="color: #94A3B8; font-size: 13px; margin: 0 0 24px 0;">Account Verification</p>
              
              <p style="color: #E2E8F0; font-size: 15px; margin: 0 0 20px 0;">
                Use the following 6-digit verification code to complete your signup:
              </p>

              <div style="background-color: #0E1118; border: 1.5px dashed #00F5A0; border-radius: 12px; padding: 18px; margin: 0 auto 24px auto; display: inline-block;">
                <span style="font-size: 38px; font-weight: 800; letter-spacing: 10px; color: #00F5A0; font-family: monospace;">{otp_code}</span>
              </div>

              <p style="color: #94A3B8; font-size: 13px; line-height: 1.5; margin: 0 0 20px 0;">
                This code is valid for <strong>15 minutes</strong>. Never share this code with anyone.
              </p>

              <hr style="border: none; border-top: 1px solid #232738; margin: 24px 0;">

              <p style="color: #64748B; font-size: 11px; margin: 0;">
                If you did not request this verification code, you can safely ignore this email.
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>"""

        msg.attach(MIMEText(plain_text, "plain"))
        msg.attach(MIMEText(html_content, "html"))

        with smtplib.SMTP("smtp.gmail.com", 587, timeout=12) as server:
            server.starttls()
            server.login(user, password)
            server.sendmail(user, recipient_email, msg.as_string())

        print(f"✅ [SMTP] Successfully sent OTP email to {recipient_email} from {user}")
        return True
    except Exception as e:
        print(f"❌ [SMTP ERROR] Failed to send email via Gmail SMTP: {e}")
        return False

# ---------------- MODELS ----------------

class SignupRequest(BaseModel):
    username: str
    email: EmailStr
    password: str
    confirm_password: str

class OTPVerifyRequest(BaseModel):
    email: EmailStr
    otp: str

class LoginRequest(BaseModel):
    identifier: str  # username OR email
    password: str


# ---------------- HELPERS ----------------

def hash_password(password: str) -> str:
    # Safely truncate to 72 bytes to adhere to bcrypt standard limits
    pwd_bytes = password.encode('utf-8')[:72]
    if HAS_BCRYPT:
        try:
            salt = bcrypt.gensalt()
            return bcrypt.hashpw(pwd_bytes, salt).decode('utf-8')
        except Exception:
            pass
    
    # Secure fallback to PBKDF2-SHA256 (standard library)
    salt = secrets.token_hex(16)
    key = hashlib.pbkdf2_hmac('sha256', pwd_bytes, salt.encode('utf-8'), 100000)
    return f"pbkdf2_sha256${salt}${key.hex()}"

def verify_password(password: str, hashed: str) -> bool:
    if not hashed or not password:
        return False
    pwd_bytes = password.encode('utf-8')[:72]
    
    # Verify standard bcrypt hash ($2a$, $2b$, $2y$)
    if (hashed.startswith("$2b$") or hashed.startswith("$2a$") or hashed.startswith("$2y$")) and HAS_BCRYPT:
        try:
            return bcrypt.checkpw(pwd_bytes, hashed.encode('utf-8'))
        except Exception:
            return False
            
    # Verify PBKDF2-SHA256 hash
    if hashed.startswith("pbkdf2_sha256$"):
        try:
            parts = hashed.split("$")
            if len(parts) == 3:
                salt = parts[1].encode('utf-8')
                stored_key_hex = parts[2]
                computed = hashlib.pbkdf2_hmac('sha256', pwd_bytes, salt, 100000).hex()
                return secrets.compare_digest(computed, stored_key_hex)
        except Exception:
            return False
            
    return False

def generate_otp() -> str:
    return str(random.randint(100000, 999999))


# ---------------- SIGNUP ----------------

@router.post("/signup")
def signup(data: SignupRequest):
    if data.password != data.confirm_password:
        raise HTTPException(status_code=400, detail="Passwords do not match")

    if len(data.password) < 6:
        raise HTTPException(status_code=400, detail="Password must be at least 6 characters")

    conn = get_db()
    cursor = conn.cursor()

    # Check if username or email is already taken by a VERIFIED (really registered) user
    cursor.execute("""
        SELECT username, email FROM users 
        WHERE (username=? OR email=?) AND is_verified=1
    """, (data.username, data.email))
    verified_user = cursor.fetchone()
    if verified_user:
        conn.close()
        if verified_user["username"].lower() == data.username.lower():
            raise HTTPException(status_code=400, detail="Username is already taken")
        else:
            raise HTTPException(status_code=400, detail="An account with this email already exists")

    # Clean up any unverified signup attempts with this username or email
    # so unverified attempts never lock the username or email
    cursor.execute("""
        DELETE FROM users 
        WHERE (username=? OR email=?) AND is_verified=0
    """, (data.username, data.email))

    otp = generate_otp()
    expiry = (datetime.utcnow() + timedelta(minutes=15)).isoformat()

    cursor.execute("""
        INSERT INTO users (username, email, password_hash, is_verified, otp_code, otp_expiry)
        VALUES (?, ?, ?, 0, ?, ?)
    """, (
        data.username,
        data.email,
        hash_password(data.password),
        otp,
        expiry
    ))

    conn.commit()
    conn.close()

    # Send OTP email via Gmail SMTP (if configured)
    email_delivered = send_otp_email(data.email, otp)

    print(f"\n==========================================")
    print(f"🔑 [AUTH] Verification OTP for {data.email}: {otp} (Delivered via Gmail: {email_delivered})")
    print(f"==========================================\n")

    return {
        "message": f"OTP sent to {data.email}",
        "otp": otp,
        "email_sent": email_delivered
    }


# ---------------- RESEND OTP ----------------

class ResendOTPRequest(BaseModel):
    email: EmailStr

@router.post("/resend-otp")
def resend_otp(data: ResendOTPRequest):
    conn = get_db()
    cursor = conn.cursor()

    cursor.execute("SELECT id, is_verified FROM users WHERE email=?", (data.email,))
    user = cursor.fetchone()

    if not user:
        conn.close()
        raise HTTPException(status_code=404, detail="No registered user found with this email")

    if user["is_verified"]:
        conn.close()
        raise HTTPException(status_code=400, detail="This account is already verified. Please sign in.")

    new_otp = generate_otp()
    new_expiry = (datetime.utcnow() + timedelta(minutes=15)).isoformat()

    cursor.execute("""
        UPDATE users
        SET otp_code=?, otp_expiry=?
        WHERE email=?
    """, (new_otp, new_expiry, data.email))

    conn.commit()
    conn.close()

    email_delivered = send_otp_email(data.email, new_otp)

    print(f"\n==========================================")
    print(f"🔄 [AUTH] Resent OTP for {data.email}: {new_otp} (Delivered via Gmail: {email_delivered})")
    print(f"==========================================\n")

    return {
        "message": f"New OTP sent to {data.email}",
        "otp": new_otp,
        "email_sent": email_delivered
    }


# ---------------- VERIFY OTP ----------------

@router.post("/verify-otp")
def verify_otp(data: OTPVerifyRequest):
    conn = get_db()
    cursor = conn.cursor()

    cursor.execute("SELECT * FROM users WHERE email=?", (data.email,))
    user = cursor.fetchone()

    if not user:
        conn.close()
        raise HTTPException(status_code=404, detail="User not found")

    if user["otp_code"] != data.otp:
        conn.close()
        raise HTTPException(status_code=400, detail="Invalid OTP")

    if user["otp_expiry"]:
        try:
            expiry_dt = datetime.fromisoformat(user["otp_expiry"])
            if datetime.utcnow() > expiry_dt:
                conn.close()
                raise HTTPException(status_code=400, detail="OTP expired. Please request a new one.")
        except ValueError:
            pass

    cursor.execute("""
        UPDATE users
        SET is_verified=1, otp_code=NULL, otp_expiry=NULL
        WHERE email=?
    """, (data.email,))

    user_id = user["id"]

    # Ensure the system 'Liked Songs' playlist exists
    cursor.execute("""
        SELECT 1 FROM playlists
        WHERE user_id=? AND is_system=1
    """, (user_id,))
    exists = cursor.fetchone()

    if not exists:
        cursor.execute("""
            INSERT INTO playlists (user_id, name, is_system)
            VALUES (?, ?, 1)
        """, (user_id, "Liked Songs"))

    conn.commit()
    conn.close()

    return {"message": "Account verified successfully"}


# ---------------- LOGIN ----------------

@router.post("/login")
def login(data: LoginRequest):
    conn = get_db()
    cursor = conn.cursor()

    cursor.execute("""
        SELECT * FROM users
        WHERE username=? OR email=?
    """, (data.identifier, data.identifier))

    user = cursor.fetchone()

    if not user:
        conn.close()
        raise HTTPException(status_code=400, detail="Invalid credentials")

    if not verify_password(data.password, user["password_hash"]):
        conn.close()
        raise HTTPException(status_code=400, detail="Invalid credentials")

    if not user["is_verified"]:
        conn.close()
        raise HTTPException(status_code=400, detail="Account not verified. Please verify with OTP first.")

    # Ensure default system playlist exists
    user_id = user["id"]
    cursor.execute("""
        SELECT 1 FROM playlists
        WHERE user_id=? AND is_system=1
    """, (user_id,))
    if not cursor.fetchone():
        cursor.execute("""
            INSERT INTO playlists (user_id, name, is_system)
            VALUES (?, 'Liked Songs', 1)
        """, (user_id,))
        conn.commit()

    conn.close()

    return {
        "message": "Login successful",
        "user_id": user["id"],
        "username": user["username"],
        "email": user["email"],
    }
