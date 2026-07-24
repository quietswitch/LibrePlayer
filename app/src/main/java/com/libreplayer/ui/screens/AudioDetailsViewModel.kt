package com.libreplayer.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.library.details.AudioDetails
import com.libreplayer.library.details.AudioDetailsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AudioDetailsScreenState(
    val isLoading: Boolean = true,
    val details: AudioDetails? = null,
    val errorMessage: String? = null,
)

class AudioDetailsViewModel(
    private val songId: String,
    private val audioDetailsRepository: AudioDetailsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AudioDetailsScreenState())
    val state: StateFlow<AudioDetailsScreenState> = _state.asStateFlow()

    init {
        load()
    }

    fun refresh() {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _state.value = AudioDetailsScreenState(isLoading = true)
            val details = audioDetailsRepository.getAudioDetails(songId)
            _state.value =
                if (details != null) {
                    AudioDetailsScreenState(
                        isLoading = false,
                        details = details,
                    )
                } else {
                    AudioDetailsScreenState(
                        isLoading = false,
                        errorMessage = "Audio details are unavailable for this track.",
                    )
                }
        }
    }

    companion object {
        fun factory(songId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LibrePlayerApplication
                AudioDetailsViewModel(
                    songId = songId,
                    audioDetailsRepository = application.appContainer.audioDetailsRepository,
                )
            }
        }
    }
}
