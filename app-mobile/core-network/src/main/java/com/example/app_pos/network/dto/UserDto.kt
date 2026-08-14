package com.example.app_pos.network.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Wire shapes for the user/profile endpoints. */

/**
 * An account. Roles are two independent booleans, so one person can be both a buyer and
 * a seller; `sellerInfo` is present exactly when [isSeller] is true, which is what lets
 * the domain model express "a seller always has shop details" as a non-null sub-object.
 */
@JsonClass(generateAdapter = true)
data class UserDto(
    @param:Json(name = "user_id") val userId: String,
    @param:Json(name = "phone") val phone: String,
    @param:Json(name = "display_name") val displayName: String,
    @param:Json(name = "is_buyer") val isBuyer: Boolean,
    @param:Json(name = "is_seller") val isSeller: Boolean,
    @param:Json(name = "email") val email: String? = null,
    @param:Json(name = "seller_info") val sellerInfo: SellerInfoDto? = null,
    @param:Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class SellerInfoDto(
    @param:Json(name = "shop_name") val shopName: String,
    @param:Json(name = "shop_phone") val shopPhone: String? = null
)

/** POST /users — register. app-pos registers sellers, app-mobile buyers. */
@JsonClass(generateAdapter = true)
data class UserCreateDto(
    @param:Json(name = "phone") val phone: String,
    @param:Json(name = "display_name") val displayName: String = "",
    @param:Json(name = "is_seller") val isSeller: Boolean
)

/** PATCH /users/me — only the fields sent are updated. */
@JsonClass(generateAdapter = true)
data class UserPatchDto(
    @param:Json(name = "display_name") val displayName: String? = null,
    @param:Json(name = "email") val email: String? = null
)

/** POST /users/me/become-seller */
@JsonClass(generateAdapter = true)
data class BecomeSellerDto(
    @param:Json(name = "shop_name") val shopName: String,
    @param:Json(name = "shop_phone") val shopPhone: String? = null
)
