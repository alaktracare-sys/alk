package com.example.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqualizerPreset(
    val name: String,
    val bandGainsDb: List<Int>, // 5 band values in dB (e.g. -12 to +12)
    val bassBoost: Int = 0,     // 0 to 1000
    val virtualizer: Int = 0    // 0 to 1000
)

class EqualizerManager(
    private val context: Context,
    private val player: ExoPlayer
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("alaktra_equalizer_prefs", Context.MODE_PRIVATE)

    companion object {
        const val TAG = "EqualizerManager"

        val DEFAULT_FREQUENCIES_HZ = listOf(60, 230, 910, 3600, 14000)
        val DEFAULT_FREQUENCY_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

        val PRESETS = listOf(
            EqualizerPreset("Flat", listOf(0, 0, 0, 0, 0), bassBoost = 0, virtualizer = 0),
            EqualizerPreset("Bass Boost", listOf(6, 4, 1, 0, 0), bassBoost = 750, virtualizer = 150),
            EqualizerPreset("Rock", listOf(5, 3, -1, 3, 5), bassBoost = 400, virtualizer = 250),
            EqualizerPreset("Pop", listOf(-1, 2, 4, 2, -1), bassBoost = 300, virtualizer = 200),
            EqualizerPreset("Jazz", listOf(3, 1, -1, 2, 4), bassBoost = 200, virtualizer = 300),
            EqualizerPreset("Hip Hop", listOf(5, 4, 0, 2, 3), bassBoost = 600, virtualizer = 200),
            EqualizerPreset("Electronic", listOf(5, 3, 0, 2, 4), bassBoost = 500, virtualizer = 350),
            EqualizerPreset("Vocal Boost", listOf(-2, 1, 5, 3, 0), bassBoost = 100, virtualizer = 100),
            EqualizerPreset("Classical", listOf(4, 3, 0, 2, 4), bassBoost = 150, virtualizer = 400),
            EqualizerPreset("Acoustic", listOf(3, 2, 1, 2, 3), bassBoost = 200, virtualizer = 200),
            EqualizerPreset("Custom", listOf(0, 0, 0, 0, 0), bassBoost = 0, virtualizer = 0)
        )
    }

    private var hwEqualizer: Equalizer? = null
    private var hwBassBoost: BassBoost? = null
    private var hwVirtualizer: Virtualizer? = null
    private var attachedSessionId: Int = -1

    // State flows for Compose UI
    private val _isEnabled = MutableStateFlow(prefs.getBoolean("eq_enabled", true))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _currentPreset = MutableStateFlow(prefs.getString("eq_preset", "Flat") ?: "Flat")
    val currentPreset: StateFlow<String> = _currentPreset.asStateFlow()

    // 5 band levels in dB: index 0..4, range -12 dB to +12 dB
    private val _bandGainsDb = MutableStateFlow(loadBandGains())
    val bandGainsDb: StateFlow<List<Int>> = _bandGainsDb.asStateFlow()

    // Bass boost strength: 0 to 1000
    private val _bassBoostStrength = MutableStateFlow(prefs.getInt("eq_bass_boost", 0))
    val bassBoostStrength: StateFlow<Int> = _bassBoostStrength.asStateFlow()

    // Virtualizer strength: 0 to 1000
    private val _virtualizerStrength = MutableStateFlow(prefs.getInt("eq_virtualizer", 0))
    val virtualizerStrength: StateFlow<Int> = _virtualizerStrength.asStateFlow()

    init {
        attachToCurrentSession()
    }

    fun attachToCurrentSession() {
        val sessionId = player.audioSessionId
        if (sessionId <= 0 || sessionId == attachedSessionId) return
        attachedSessionId = sessionId
        releaseEffects()

        try {
            hwEqualizer = Equalizer(0, sessionId).apply {
                enabled = _isEnabled.value
            }
            applyBandsToHardware()
        } catch (e: Throwable) {
            Log.w(TAG, "Hardware Equalizer not supported on this device/session", e)
            hwEqualizer = null
        }

        try {
            hwBassBoost = BassBoost(0, sessionId).apply {
                if (strengthSupported) {
                    setStrength(_bassBoostStrength.value.toShort())
                }
                enabled = _isEnabled.value && _bassBoostStrength.value > 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Hardware BassBoost not supported", e)
            hwBassBoost = null
        }

        try {
            hwVirtualizer = Virtualizer(0, sessionId).apply {
                if (strengthSupported) {
                    setStrength(_virtualizerStrength.value.toShort())
                }
                enabled = _isEnabled.value && _virtualizerStrength.value > 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Hardware Virtualizer not supported", e)
            hwVirtualizer = null
        }
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs.edit().putBoolean("eq_enabled", enabled).apply()
        try {
            hwEqualizer?.enabled = enabled
            hwBassBoost?.enabled = enabled && _bassBoostStrength.value > 0
            hwVirtualizer?.enabled = enabled && _virtualizerStrength.value > 0
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to toggle hardware equalizer effects", e)
        }
    }

    fun selectPreset(presetName: String) {
        val preset = PRESETS.firstOrNull { it.name.equals(presetName, ignoreCase = true) } ?: return
        _currentPreset.value = preset.name
        prefs.edit().putString("eq_preset", preset.name).apply()

        if (preset.name != "Custom") {
            setBandGains(preset.bandGainsDb)
            setBassBoost(preset.bassBoost)
            setVirtualizer(preset.virtualizer)
        }
    }

    fun setBandGain(bandIndex: Int, gainDb: Int) {
        if (bandIndex !in 0..4) return
        val clamped = gainDb.coerceIn(-12, 12)
        val updated = _bandGainsDb.value.toMutableList()
        updated[bandIndex] = clamped
        _bandGainsDb.value = updated
        _currentPreset.value = "Custom"

        saveBandGains(updated)
        prefs.edit().putString("eq_preset", "Custom").apply()
        applyBandToHardware(bandIndex, clamped)
    }

    fun setBandGains(gains: List<Int>) {
        val normalized = (0..4).map { idx -> gains.getOrElse(idx) { 0 }.coerceIn(-12, 12) }
        _bandGainsDb.value = normalized
        saveBandGains(normalized)
        applyBandsToHardware()
    }

    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _bassBoostStrength.value = clamped
        prefs.edit().putInt("eq_bass_boost", clamped).apply()
        try {
            hwBassBoost?.apply {
                if (strengthSupported) {
                    setStrength(clamped.toShort())
                }
                enabled = _isEnabled.value && clamped > 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error setting bass boost", e)
        }
    }

    fun setVirtualizer(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _virtualizerStrength.value = clamped
        prefs.edit().putInt("eq_virtualizer", clamped).apply()
        try {
            hwVirtualizer?.apply {
                if (strengthSupported) {
                    setStrength(clamped.toShort())
                }
                enabled = _isEnabled.value && clamped > 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error setting virtualizer", e)
        }
    }

    fun resetToFlat() {
        selectPreset("Flat")
    }

    private fun applyBandsToHardware() {
        val eq = hwEqualizer ?: return
        try {
            val numBands = eq.numberOfBands.toInt()
            val minMb = eq.bandLevelRange?.get(0)?.toInt() ?: -1200
            val maxMb = eq.bandLevelRange?.get(1)?.toInt() ?: 1200

            for (i in 0 until minOf(numBands, 5)) {
                val db = _bandGainsDb.value.getOrElse(i) { 0 }
                // Convert dB (-12..+12) to milliBels (-1200..+1200)
                val mb = (db * 100).coerceIn(minMb, maxMb).toShort()
                eq.setBandLevel(i.toShort(), mb)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error applying bands to hardware equalizer", e)
        }
    }

    private fun applyBandToHardware(bandIndex: Int, gainDb: Int) {
        val eq = hwEqualizer ?: return
        try {
            if (bandIndex < eq.numberOfBands) {
                val minMb = eq.bandLevelRange?.get(0)?.toInt() ?: -1200
                val maxMb = eq.bandLevelRange?.get(1)?.toInt() ?: 1200
                val mb = (gainDb * 100).coerceIn(minMb, maxMb).toShort()
                eq.setBandLevel(bandIndex.toShort(), mb)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error applying band $bandIndex to hardware", e)
        }
    }

    private fun loadBandGains(): List<Int> {
        return (0..4).map { idx ->
            prefs.getInt("eq_band_$idx", 0).coerceIn(-12, 12)
        }
    }

    private fun saveBandGains(gains: List<Int>) {
        val editor = prefs.edit()
        gains.forEachIndexed { index, gain ->
            editor.putInt("eq_band_$index", gain)
        }
        editor.apply()
    }

    fun releaseEffects() {
        try {
            hwEqualizer?.release()
        } catch (_: Throwable) {}
        try {
            hwBassBoost?.release()
        } catch (_: Throwable) {}
        try {
            hwVirtualizer?.release()
        } catch (_: Throwable) {}
        hwEqualizer = null
        hwBassBoost = null
        hwVirtualizer = null
    }
}
