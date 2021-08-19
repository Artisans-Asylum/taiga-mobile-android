package io.eugenethedev.taigamobile.dagger

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Binds
import dagger.Module
import dagger.Provides
import io.eugenethedev.taigamobile.BuildConfig
import io.eugenethedev.taigamobile.Session
import io.eugenethedev.taigamobile.Settings
import io.eugenethedev.taigamobile.data.api.RefreshTokenRequest
import io.eugenethedev.taigamobile.data.api.RefreshTokenResponse
import io.eugenethedev.taigamobile.data.api.TaigaApi
import io.eugenethedev.taigamobile.data.repositories.*
import io.eugenethedev.taigamobile.domain.repositories.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Singleton

@Module
class DataModule {

    @Singleton
    @Provides
    fun provideTaigaApi(session: Session, gson: Gson): TaigaApi {
        val baseUrlPlaceholder = "https://nothing.nothing"

        val okHttpBuilder = OkHttpClient.Builder()
            .addInterceptor {
                it.run {
                    val url = it.request().url.toUrl().toExternalForm()

                    proceed(
                        request()
                            .newBuilder()
                            .url(url.replace(baseUrlPlaceholder, "https://${session.server}/${TaigaApi.API_PREFIX}"))
                            .addHeader("User-Agent", "TaigaMobile/${BuildConfig.VERSION_NAME}")
                            .also {
                                if ("/${TaigaApi.AUTH_ENDPOINTS}" !in url) { // do not add Authorization header to authorization requests
                                    it.addHeader("Authorization", "Bearer ${session.token}")
                                }
                            }
                            .build()
                    )
                }
            }
            .addInterceptor(
                HttpLoggingInterceptor(Timber::d)
                    .setLevel(HttpLoggingInterceptor.Level.BODY)
                    .also { it.redactHeader("Authorization") }
            )

        val tokenClient = okHttpBuilder.build()

        return Retrofit.Builder()
            .baseUrl(baseUrlPlaceholder) // base url is set dynamically in interceptor
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(
                okHttpBuilder.authenticator { route, response ->
                        response.request.header("Authorization")?.let {
                            // refresh token
                            val body = gson.toJson(RefreshTokenRequest(session.refreshToken))

                            val request = Request.Builder()
                                .url("$baseUrlPlaceholder/${TaigaApi.REFRESH_ENDPOINT}")
                                .post(body.toRequestBody("application/json".toMediaType()))
                                .build()

                            try {
                                val refreshResponse = gson.fromJson(
                                    tokenClient.newCall(request).execute().body?.string(),
                                    RefreshTokenResponse::class.java
                                )

                                session.token = refreshResponse.auth_token
                                session.refreshToken = refreshResponse.refresh

                                response.request.newBuilder()
                                    .removeHeader("Authorization")
                                    .addHeader("Authorization", "Bearer ${session.token}")
                                    .build()
                            } catch (e: Exception) {
                                session.token = ""
                                session.refreshToken = ""
                                null
                            }
                        }
                    }
                    .build()
            )
            .build()
            .create(TaigaApi::class.java)
    }

    @Provides
    fun provideGson(): Gson = GsonBuilder().serializeNulls()
        .registerTypeAdapter(LocalDate::class.java, LocalDateTypeAdapter().nullSafe())
        .registerTypeAdapter(LocalDateTime::class.java, LocalDateTimeTypeAdapter().nullSafe())
        .create()

    @Provides
    @Singleton
    fun provideSession(context: Context) = Session(context)

    @Provides
    @Singleton
    fun provideSettings(context: Context) = Settings(context)
}

@Module
abstract class RepositoriesModule {
    @Singleton
    @Binds
    abstract fun bindIAuthRepository(authRepository: AuthRepository): IAuthRepository

    @Singleton
    @Binds
    abstract fun bindISearchRepository(searchRepository: SearchRepository): ISearchRepository

    @Singleton
    @Binds
    abstract fun bindIStoriesRepository(storiesRepository: TasksRepository): ITasksRepository

    @Singleton
    @Binds
    abstract fun bindIUsersRepository(usersRepository: UsersRepository): IUsersRepository

    @Singleton
    @Binds
    abstract fun bindISprintsRepository(sprintsRepository: SprintsRepository): ISprintsRepository
}
