package com.podcastapp.data.remote

import com.podcastapp.data.ProgressOwner
import com.podcastapp.data.filemedia.FileEntriesResponse
import com.podcastapp.data.filemedia.FileSourcesResponse
import com.podcastapp.data.filemedia.FileTicketRequest
import com.podcastapp.data.filemedia.FileTicketResponse
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * 服务端 REST 接口。所有路径相对 [BASE_URL] 配置，默认 /api/v1。
 */
interface PodcastApi {

    @GET("healthz")
    suspend fun health(): Map<String, String>

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest, @Tag owner: ProgressOwner? = null): AuthResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): StatusResponse

    @GET("me")
    suspend fun me(): UserEnvelope

    @PATCH("me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): UserEnvelope

    @POST("me/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): StatusResponse

    @GET("settings")
    suspend fun getSettings(): SettingsDto

    @PUT("settings")
    suspend fun saveSettings(@Body body: SettingsDto): SettingsDto

    @GET("podcasts")
    suspend fun podcasts(
        @Query("scope") scope: String? = null,
        @Query("q") query: String? = null,
        @Query("category") category: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): PodcastList

    @POST("podcasts")
    suspend fun addPodcast(@Body body: AddPodcastRequest): PodcastEnvelope

    @GET("podcasts/{id}")
    suspend fun podcast(@Path("id") id: String): PodcastDto

    @POST("podcasts/{id}/refresh")
    suspend fun refreshPodcast(@Path("id") id: String): PodcastEnvelope

    @GET("podcasts/{id}/episodes")
    suspend fun episodes(
        @Path("id") podcastId: String,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): EpisodeList

    @GET("episodes/{id}")
    suspend fun episode(@Path("id") id: String): EpisodeEnvelope

    @GET("discover/search")
    suspend fun discover(@Query("q") query: String, @Query("limit") limit: Int = 25): DiscoverList

    // ---- 管理员配置的只读文件音源 ----
    @GET("media/sources")
    suspend fun fileSources(@Tag owner: ProgressOwner): FileSourcesResponse

    @GET("media/entries")
    suspend fun fileEntries(
        @Query("source") source: String,
        @Query("path") path: String,
        @Tag owner: ProgressOwner,
    ): FileEntriesResponse

    @POST("media/ticket")
    suspend fun fileTicket(@Body body: FileTicketRequest, @Tag owner: ProgressOwner): FileTicketResponse

    // ---- 网络电台 ----
    @GET("stations")
    suspend fun stations(): StationList

    @POST("stations")
    suspend fun addStation(@Body body: AddStationRequest): StationEnvelope

    @DELETE("stations/{id}")
    suspend fun deleteStation(@Path("id") id: String): StatusResponse

    @PUT("stations/{id}/favorite")
    suspend fun favoriteStation(@Path("id") id: String, @Body body: FavoriteRequest = FavoriteRequest()): FavoriteResponse

    @DELETE("stations/{id}/favorite")
    suspend fun unfavoriteStation(@Path("id") id: String): FavoriteResponse

    @GET("stations/probe")
    suspend fun probeStation(@Query("url") url: String): StreamProbeDto

    @GET("discover/stations")
    suspend fun discoverStations(
        @Query("q") query: String? = null,
        @Query("country") country: String? = null,
        @Query("tag") tag: String? = null,
        @Query("limit") limit: Int = 30,
    ): StationCandidateList

    @POST("stations/import")
    suspend fun importStations(@Body body: ImportStationsRequest): ImportStationsResponse

    @GET("subscriptions")
    suspend fun subscriptions(): PodcastList

    @PUT("subscriptions/{podcastId}")
    suspend fun subscribe(@Path("podcastId") podcastId: String, @Body body: SubscribeRequest = SubscribeRequest()): Map<String, Boolean>

    @DELETE("subscriptions/{podcastId}")
    suspend fun unsubscribe(@Path("podcastId") podcastId: String): Map<String, Boolean>

    @GET("progress")
    suspend fun progressList(@Query("limit") limit: Int = 50): ProgressList

    @GET("progress/{episodeId}")
    suspend fun progress(@Path("episodeId") episodeId: String, @Tag owner: ProgressOwner): ProgressDto

    @PUT("progress/{episodeId}")
    suspend fun saveProgress(@Path("episodeId") episodeId: String, @Body body: ProgressRequest, @Tag owner: ProgressOwner): StatusResponse

    @GET("actions")
    suspend fun actions(@Query("type") type: String? = null): ActionList

    @PUT("actions/{episodeId}")
    suspend fun saveAction(@Path("episodeId") episodeId: String, @Body body: ActionRequest): ActionDto

    @GET("playlists")
    suspend fun playlists(): PlaylistList

    @POST("playlists")
    suspend fun createPlaylist(@Body body: CreatePlaylistRequest): PlaylistList

    @GET("playlists/{id}")
    suspend fun playlist(@Path("id") id: String): PlaylistDetail

    @DELETE("playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: String): StatusResponse

    @PUT("playlists/{id}/items/{episodeId}")
    suspend fun addPlaylistItem(@Path("id") id: String, @Path("episodeId") episodeId: String): StatusResponse

    @DELETE("playlists/{id}/items/{episodeId}")
    suspend fun removePlaylistItem(@Path("id") id: String, @Path("episodeId") episodeId: String): StatusResponse

    @POST("sync")
    suspend fun sync(@Body body: SyncRequest, @Tag owner: ProgressOwner): SyncSnapshot
}
