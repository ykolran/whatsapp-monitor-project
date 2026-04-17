package com.ykolran.wam.api

import com.ykolran.wam.models.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("api/messages")
    suspend fun postMessage(@Body message: Message): Response<SimpleResponse>

    @GET("api/conversations")
    suspend fun getConversations(): Response<List<Conversation>>

    @Multipart
    @POST("api/images")
    suspend fun uploadImage(
        @Part image: MultipartBody.Part,
        @Part("conversationId") conversationId: RequestBody?
    ): Response<ImageUploadResponse>

    @GET("api/images/children")
    suspend fun getChildImages(): Response<List<ChildImage>>

    @Multipart
    @POST("api/faces/enroll")
    suspend fun enrollFace(
        @Part photo: MultipartBody.Part,
        @Part("childName") childName: RequestBody
    ): Response<SimpleResponse>

    @GET("api/health")
    suspend fun healthCheck(): Response<SimpleResponse>
}
