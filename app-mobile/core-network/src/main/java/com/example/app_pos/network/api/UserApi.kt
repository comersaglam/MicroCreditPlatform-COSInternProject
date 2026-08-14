package com.example.app_pos.network.api

import com.example.app_pos.network.dto.BecomeSellerDto
import com.example.app_pos.network.dto.UserCreateDto
import com.example.app_pos.network.dto.UserDto
import com.example.app_pos.network.dto.UserPatchDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST

/** Account and profile. "me" is always the bearer token's user, never a path parameter. */
interface UserApi {

    /** Registration, idempotent by phone: an existing number returns the existing user. */
    @POST("users")
    suspend fun register(@Body body: UserCreateDto): UserDto

    @GET("users/me")
    suspend fun me(): UserDto

    /** Only the fields present are changed; omitted ones are left alone. */
    @PATCH("users/me")
    suspend fun updateProfile(@Body body: UserPatchDto): UserDto

    /**
     * Flips the account into a seller. Roles are additive — someone can run a shop and
     * still owe money at another one — so this never clears the buyer role.
     */
    @POST("users/me/become-seller")
    suspend fun becomeSeller(@Body body: BecomeSellerDto): UserDto
}
