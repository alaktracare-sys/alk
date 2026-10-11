package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ui.screens.MainShell
import com.example.ui.screens.auth.LoginScreen
import com.example.ui.screens.auth.SignupScreen
import com.example.ui.screens.auth.WelcomeScreen
import com.example.ui.theme.AlaktraTheme

enum class AuthDestination {
    WELCOME, LOGIN, SIGNUP, MAIN
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        // Request POST_NOTIFICATIONS permission on Android 13+ for persistent background media controls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        val app = application as AlreadyApp
        val repository = app.repository
        val audioController = app.audioController

        setContent {
            AlaktraTheme {
                val initialAuth = remember {
                    if (repository.authPreferences.isLoggedIn()) {
                        AuthDestination.MAIN
                    } else {
                        AuthDestination.WELCOME
                    }
                }
                var currentDestination by remember { mutableStateOf(initialAuth) }

                when (currentDestination) {
                    AuthDestination.WELCOME -> {
                        WelcomeScreen(
                            onNavigateToLogin = { currentDestination = AuthDestination.LOGIN },
                            onNavigateToSignup = { currentDestination = AuthDestination.SIGNUP },
                            onContinueAsGuest = { currentDestination = AuthDestination.MAIN }
                        )
                    }

                    AuthDestination.LOGIN -> {
                        LoginScreen(
                            repository = repository,
                            onBack = { currentDestination = AuthDestination.WELCOME },
                            onLoginSuccess = { currentDestination = AuthDestination.MAIN },
                            onNavigateToSignup = { currentDestination = AuthDestination.SIGNUP }
                        )
                    }

                    AuthDestination.SIGNUP -> {
                        SignupScreen(
                            repository = repository,
                            onBack = { currentDestination = AuthDestination.WELCOME },
                            onSignupSuccess = { currentDestination = AuthDestination.MAIN },
                            onNavigateToLogin = { currentDestination = AuthDestination.LOGIN }
                        )
                    }

                    AuthDestination.MAIN -> {
                        MainShell(
                            repository = repository,
                            audioController = audioController,
                            onLogout = { currentDestination = AuthDestination.WELCOME }
                        )
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val audioController = (application as? AlreadyApp)?.audioController
        if (audioController != null && event?.repeatCount == 0) {
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    audioController.play()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    audioController.pause()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                    audioController.togglePlayPause()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    audioController.next()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    audioController.previous()
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                    audioController.pause()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
