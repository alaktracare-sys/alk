package com.example.soundcapsule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.YearMonth

class SoundCapsuleViewModel(
    private val musicRepository: MusicRepository,
) : ViewModel() {

    private val capsuleRepo = musicRepository.soundCapsuleRepository

    private var activeUserId: Int = capsuleRepo.getEffectiveUserId()
    private var userManuallyPickedMonth: Boolean = false

    private val _selectedMonth = MutableStateFlow(capsuleRepo.getDefaultActiveMonth(activeUserId))
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth.asStateFlow()

    val availableMonths: StateFlow<List<YearMonth>> = capsuleRepo.availableMonthsFlow

    private val _monthlyFeed = MutableStateFlow(capsuleRepo.getInstantMonthlyFeed(activeUserId))
    val monthlyFeed: StateFlow<List<SoundCapsuleStats>> = _monthlyFeed.asStateFlow()

    private val _stats = MutableStateFlow(
        capsuleRepo.getInstantStats(activeUserId, _selectedMonth.value)
    )
    val stats: StateFlow<SoundCapsuleStats> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            capsuleRepo.monthlyFeedFlow.collect { feed ->
                if (feed.isNotEmpty()) {
                    _monthlyFeed.value = feed
                }
            }
        }
        viewModelScope.launch {
            combine(
                _selectedMonth,
                capsuleRepo.hotStatsVersion,
            ) { month, _ ->
                month
            }.collect { month ->
                val uid = activeUserId
                _monthlyFeed.value = capsuleRepo.getInstantMonthlyFeed(uid)
                if (!userManuallyPickedMonth) {
                    val smartMonth = capsuleRepo.getDefaultActiveMonth(uid)
                    if (smartMonth != month) {
                        _selectedMonth.value = smartMonth
                        _stats.value = capsuleRepo.getInstantStats(uid, smartMonth)
                        return@collect
                    }
                }
                val hot = capsuleRepo.getCachedStatsOrNull(uid, month)
                if (hot != null) {
                    _stats.value = hot
                } else {
                    _stats.value = capsuleRepo.loadMonthStats(uid, month)
                }
            }
        }
    }

    /**
     * Synchronously aligns ViewModel state with the repository's precomputed hot snapshot
     * before Frame #1 of Sound Capsule is drawn.
     */
    fun onScreenOpened() {
        val uid = capsuleRepo.getEffectiveUserId()
        if (uid != activeUserId) {
            activeUserId = uid
            userManuallyPickedMonth = false
        }
        _monthlyFeed.value = capsuleRepo.getInstantMonthlyFeed(uid)
        val targetMonth = if (userManuallyPickedMonth) {
            _selectedMonth.value
        } else {
            capsuleRepo.getDefaultActiveMonth(uid)
        }
        if (_selectedMonth.value != targetMonth) {
            _selectedMonth.value = targetMonth
        }
        _stats.value = capsuleRepo.getInstantStats(uid, targetMonth)

        if (capsuleRepo.getCachedStatsOrNull(uid, targetMonth) == null) {
            viewModelScope.launch {
                _stats.value = capsuleRepo.loadMonthStats(uid, targetMonth)
            }
        }
        viewModelScope.launch {
            capsuleRepo.syncWithServer(uid, force = false)
        }
    }

    fun selectMonth(month: YearMonth) {
        userManuallyPickedMonth = true
        _selectedMonth.value = month
        capsuleRepo.getCachedStatsOrNull(activeUserId, month)?.let { cached ->
            _stats.value = cached
        }
    }

    fun refresh() {
        onScreenOpened()
    }

    class Factory(private val repository: MusicRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SoundCapsuleViewModel(repository) as T
        }
    }
}
