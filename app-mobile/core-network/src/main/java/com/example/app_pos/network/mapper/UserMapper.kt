package com.example.app_pos.network.mapper

import com.example.app_pos.model.SellerInfo
import com.example.app_pos.model.User
import com.example.app_pos.network.dto.BecomeSellerDto
import com.example.app_pos.network.dto.SellerInfoDto
import com.example.app_pos.network.dto.UserCreateDto
import com.example.app_pos.network.dto.UserDto

/**
 * User ↔ wire. The interesting part is SellerInfo: the domain keeps it as a nullable
 * sub-object so "is a seller ⇒ has shop details" is enforced by the type system, and the
 * mapper is where that invariant is re-established from data that could contradict it.
 */

fun UserDto.toDomain(): User = User(
    userId = userId,
    phone = phone,
    displayName = displayName,
    isBuyer = isBuyer,
    isSeller = isSeller,
    email = email,
    // Trust the flag, not just the presence of the block: a server that sends shop
    // details for a non-seller must not produce a User that violates the invariant.
    sellerInfo = sellerInfo?.takeIf { isSeller }?.toDomain(),
    createdAt = createdAt
)

fun SellerInfoDto.toDomain(): SellerInfo = SellerInfo(
    shopName = shopName,
    shopPhone = shopPhone
)

fun SellerInfo.toDto(): SellerInfoDto = SellerInfoDto(
    shopName = shopName,
    shopPhone = shopPhone
)

/** Registration. app-pos registers sellers; app-mobile registers buyers. */
fun userCreateDto(phone: String, displayName: String, isSeller: Boolean): UserCreateDto =
    UserCreateDto(phone = phone, displayName = displayName, isSeller = isSeller)

fun SellerInfo.toBecomeSellerDto(): BecomeSellerDto = BecomeSellerDto(
    shopName = shopName,
    shopPhone = shopPhone
)
