package com.unityaura.di

import android.content.Context
import com.google.gson.Gson
import com.unityaura.db.AppDatabase
import com.unityaura.db.EventDao
import com.unityaura.network.UploadApi
import com.unityaura.tracker.ConcurrentEventTracker
import com.unityaura.tracker.IEventTracker
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ApplicationInjector(private val context: Context) : IApplicationInjector {

    override val gson: Gson by lazy { Gson() }

    override val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.escuelajs.co/")
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    override val database: AppDatabase by lazy {
        AppDatabase.create(context)
    }

    override val eventDao: EventDao by lazy {
        database.eventDao()
    }

    override val uploadApi: UploadApi by lazy {
        retrofit.create(UploadApi::class.java)
    }

    override val eventTracker: IEventTracker by lazy {
        ConcurrentEventTracker(
            eventDao = eventDao,
            uploadApi = uploadApi,
            gson = gson,
            context = context
        )
    }
}
