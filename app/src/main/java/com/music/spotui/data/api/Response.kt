package com.music.spotui.data.api

sealed class Response<T> {

    data class Loading<T>(val partialData: T? = null) : Response<T>()
    data class Success<T>(val data: T) : Response<T>()
    data class Error<T>(val error: String) : Response<T>()
}

val <T> Response<T>.dataOrNull: T?
    get() = when (this) {
        is Response.Success -> data
        is Response.Loading -> partialData
        is Response.Error -> null
    }

val <T> Response<T>.isLoading: Boolean
    get() = this is Response.Loading
