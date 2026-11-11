package com.music.spotui.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.LyricsApi
import com.music.spotui.data.api.TranslationApi
import com.music.spotui.data.entity.LyricLine
import com.music.spotui.data.entity.Lyrics
import com.music.spotui.data.preferences.getSourceLanguage
import com.music.spotui.data.preferences.getTargetLanguage
import com.music.spotui.data.preferences.setSourceLanguage
import com.music.spotui.data.preferences.setTargetLanguage
import com.music.spotui.data.preferences.TranslationCachePref
import com.music.spotui.data.preferences.translationCacheKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LyricsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    sealed class State {
        object Loading : State()
        data class Loaded(val lyrics: Lyrics, val requestKey:String="") : State()
        object NotFound : State()
    }

    sealed class ModelState {
        object Idle : ModelState()
        object Downloading : ModelState()
        object Ready : ModelState()
        data class Failed(val message: String) : ModelState()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    @Volatile private var loadedKey: String? = null
    private var generation=0L
    private var translationSuppressed=false
    private var loadedTitle: String? = null
    private var loadedArtist: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var translationJob: kotlinx.coroutines.Job? = null

    var showLanguageBar by mutableStateOf(false)
        private set

    var translationsLoading by mutableStateOf(false)
        private set
    var translatedLines by mutableStateOf<List<LyricLine>?>(null)
        private set
    var translationError by mutableStateOf<String?>(null)
        private set

    var modelState by mutableStateOf<ModelState>(ModelState.Idle)
        private set

    var sourceLanguage by mutableStateOf(getSourceLanguage(context, "en"))
        private set
    var targetLanguage by mutableStateOf(getTargetLanguage(context, Locale.getDefault().language))
        private set
    val availableLanguages: List<Pair<String, String>> = TranslationApi.languages

    val translationsVisible: Boolean get() = translatedLines != null

    fun visibleState(requestKey:String,value:State):State =
        if(loadedKey==requestKey && (value !is State.Loaded || value.requestKey==requestKey))value else State.Loading

    fun load(title: String, artist: String, album: String, durationSec: Int, trackId: String = "") {
        val key = requestKey(title,artist,album,durationSec,trackId)
        if (loadedKey == key && _state.value !is State.NotFound) return
        val token=++generation
        loadJob?.cancel()
        loadedKey=key;loadedTitle=title;loadedArtist=artist
        _state.value=State.Loading;resetTranslationState();translationSuppressed=false
        loadJob=viewModelScope.launch(Dispatchers.IO) {
            try {
                val lyrics=LyricsApi.fetch(title,artist,album,durationSec,trackId)
                withContext(Dispatchers.Main) {
                    if(token!=generation || loadedKey!=key)return@withContext
                    _state.value=if(lyrics==null || lyrics.isEmpty)State.NotFound else State.Loaded(lyrics,key)
                    restoreCachedTranslation(title,artist,lyrics)
                }
                if(lyrics!=null && !lyrics.isEmpty) {
                    val detected=try {TranslationApi.detectLanguage(lyrics.lines.joinToString(" "){it.text})}
                        catch(c:kotlinx.coroutines.CancellationException){throw c}catch(_:Exception){null}
                    withContext(Dispatchers.Main) {
                        if(token!=generation || loadedKey!=key || translationJob?.isActive==true || showLanguageBar)return@withContext
                        if(detected!=null)sourceLanguage=detected
                        restoreCachedTranslation(title,artist,lyrics)
                    }
                }
            } catch(c:kotlinx.coroutines.CancellationException){throw c}
            catch(_:Exception){withContext(Dispatchers.Main){if(token==generation && loadedKey==key)_state.value=State.NotFound}}
        }
    }

    private fun restoreCachedTranslation(title: String, artist: String, lyrics: Lyrics?) {
        if (translationSuppressed || lyrics == null || lyrics.isEmpty) return
        val key = translationCacheKey(loadedKey ?: title, artist, sourceLanguage, targetLanguage)
        val cached = TranslationCachePref.get(context, key)
        if (cached != null && cached.size == lyrics.lines.size) {
            translatedLines = cached
        }
    }

    private fun persistCachedTranslation(lines: List<LyricLine>) {
        val title = loadedTitle ?: return
        val artist = loadedArtist ?: return
        if (lines.isEmpty()) return
        val key = translationCacheKey(loadedKey ?: title, artist, sourceLanguage, targetLanguage)
        TranslationCachePref.put(context, key, lines)
    }

    private fun translationCacheKeyForCurrent(): String? {
        val title = loadedTitle ?: return null
        val artist = loadedArtist ?: return null
        return translationCacheKey(loadedKey ?: title, artist, sourceLanguage, targetLanguage)
    }

    fun toggleLanguageBar() {
        showLanguageBar = !showLanguageBar
    }

    fun dismissTranslate() {
        translationSuppressed=true
        translationJob?.cancel()
        translationJob = null
        // Remove from disk so it won't auto-show when lyrics load again.
        translationCacheKeyForCurrent()?.let { TranslationCachePref.remove(context, it) }
        showLanguageBar = false
        translatedLines = null
        translationsLoading = false
        translationError = null
        modelState = ModelState.Idle
    }

    fun clearError() { translationError = null }

    fun startTranslation() {
        if (translationsLoading) return
        val current = _state.value
        if (current !is State.Loaded) return

        if (sourceLanguage == targetLanguage) {
            translationError = "Source and target language must be different"
            return
        }

        val translatable = current.lyrics.lines.count { it.text.isNotBlank() }
        if (translatable == 0) {
            translationError = "No translatable lyrics found"
            return
        }

        val request=loadedKey
        val source=sourceLanguage;val target=targetLanguage
        fun stillCurrent()=loadedKey==request && sourceLanguage==source && targetLanguage==target && !translationSuppressed
        translationSuppressed=false
        translationError = null
        translationsLoading = true
        translatedLines = null
        modelState = ModelState.Downloading

        translationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Downloading model: $sourceLanguage → $targetLanguage")
                TranslationApi.ensureModel(source, target)
                withContext(Dispatchers.Main) { if(stillCurrent())modelState = ModelState.Ready }

                Log.d(TAG, "Starting translation: $sourceLanguage → $targetLanguage ($translatable lines)")
                val result = TranslationApi.translateLines(
                    lines = current.lyrics.lines,
                    sourceLang = source,
                    targetLang = target,
                )
                val translatedCount = result.count { it.translatedText != null }
                Log.d(TAG, "Translation complete: $translatedCount/${result.size} lines translated")
                withContext(Dispatchers.Main) {
                    if(!stillCurrent())return@withContext
                    if (translatedCount == 0) {
                        translationError = "Translation failed — no lines were translated."
                    } else {
                        translatedLines = result
                        // Persist to disk so it auto-shows on next load.
                        persistCachedTranslation(result)
                    }
                    translationsLoading = false
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed", e)
                withContext(Dispatchers.Main) {
                    if(!stillCurrent())return@withContext
                    modelState = ModelState.Failed(e.localizedMessage ?: "Model download failed")
                    translationError = "Translation failed: ${e.localizedMessage ?: "Unknown error"}"
                    translationsLoading = false
                }
            }
        }
    }

    fun chooseSourceLanguage(langCode: String) {
        translationJob?.cancel();translationJob=null;translationsLoading=false
        if (langCode == sourceLanguage) return
        sourceLanguage = langCode
        setSourceLanguage(context, langCode)
        translationError = null
        translatedLines = null
    }

    fun chooseTargetLanguage(langCode: String) {
        translationJob?.cancel();translationJob=null;translationsLoading=false
        if (langCode == targetLanguage) return
        targetLanguage = langCode
        setTargetLanguage(context, langCode)
        translationError = null
        translatedLines = null
    }

    fun swapLanguages() {
        translationJob?.cancel();translationJob=null;translationsLoading=false
        val tmp = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = tmp
        setSourceLanguage(context, sourceLanguage)
        setTargetLanguage(context, targetLanguage)
        translationError = null
        translatedLines = null
    }

    private fun resetTranslationState() {
        translationJob?.cancel()
        translationJob = null
        showLanguageBar = false
        translationsLoading = false
        translatedLines = null
        translationError = null
        modelState = ModelState.Idle
    }

    companion object {
        fun requestKey(title:String,artist:String,album:String,durationSec:Int,trackId:String) = "$title|$artist|$album|$durationSec|$trackId"
        private const val TAG = "LyricsVM"
    }
}
