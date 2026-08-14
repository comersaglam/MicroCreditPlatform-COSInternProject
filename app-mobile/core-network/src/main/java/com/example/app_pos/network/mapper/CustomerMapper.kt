package com.example.app_pos.network.mapper

import com.example.app_pos.model.ClaimStatus
import com.example.app_pos.model.Customer
import com.example.app_pos.network.dto.CustomerCreateDto
import com.example.app_pos.network.dto.CustomerDto

/**
 * Customer ↔ wire.
 *
 * Note where balanceMinor comes from: the SERVER derives it from the ledger and sends it,
 * whereas the Room mapper receives it as a parameter computed on-device. Same domain
 * type, two provenances — both derived, never stored.
 */

fun CustomerDto.toDomain(): Customer {
    val status = claimStatus.toClaimStatusOrUnclaimed()
    return Customer(
        customerId = customerId,
        displayName = displayName,
        phone = phone,
        claimStatus = status,
        // Keep the "CLAIMED ⇔ a linked user id exists" invariant even if the payload
        // disagrees with itself, so downstream code can rely on the pair.
        claimedByUserId = claimedByUserId?.takeIf { status == ClaimStatus.CLAIMED },
        balanceMinor = balanceMinor
    )
}

fun customerCreateDto(displayName: String, phone: String): CustomerCreateDto =
    CustomerCreateDto(displayName = displayName, phone = phone)
