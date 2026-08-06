package com.example.nunarecorder.network

import okhttp3.Credentials
import okhttp3.Request

fun Request.Builder.withServerBasicAuth(
    username: String,
    password: String
): Request.Builder = apply {
    if (username.isNotBlank() && password.isNotBlank()) {
        header("Authorization", Credentials.basic(username, password))
    }
}
