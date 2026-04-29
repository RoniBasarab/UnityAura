package com.unityaura.di

import com.google.gson.Gson
import com.unityaura.db.AppDatabase
import com.unityaura.db.EventDao
import com.unityaura.network.UploadApi
import com.unityaura.tracker.IEventTracker
import retrofit2.Retrofit

interface IApplicationInjector {
    val gson: Gson
    val retrofit: Retrofit
    val database: AppDatabase
    val eventDao: EventDao
    val uploadApi: UploadApi
    val eventTracker: IEventTracker
}
