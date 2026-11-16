package com.example.data.remote

import com.example.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

class AppSecurityInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val timestamp = System.currentTimeMillis().toString()
        val path = originalRequest.url.encodedPath
        
        val appSecret = BuildConfig.API_SECRET
        val hmacSecret = BuildConfig.HMAC_SECRET
        val signature = generateHmacSha256("$path:$timestamp", hmacSecret)

        val securedRequest = originalRequest.newBuilder()
            .header("X-App-Secret", appSecret)
            .header("X-Timestamp", timestamp)
            .header("X-Signature", signature)
            .header("User-Agent", "AlaktraAndroidApp/${BuildConfig.VERSION_NAME}")
            .header("X-Requested-With", "com.example.musicplayer")
            .build()

        return chain.proceed(securedRequest)
    }

    private fun generateHmacSha256(data: String, secret: String): String {
        try {
            val sha256Hmac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
            sha256Hmac.init(secretKey)
            val hashBytes = sha256Hmac.doFinal(data.toByteArray(Charsets.UTF_8))
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Base64.getEncoder().encodeToString(hashBytes)
            } else {
                android.util.Base64.encodeToString(hashBytes, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            return ""
        }
    }
}
