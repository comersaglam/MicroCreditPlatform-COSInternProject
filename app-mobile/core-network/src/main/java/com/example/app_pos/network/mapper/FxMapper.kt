package com.example.app_pos.network.mapper

import com.example.app_pos.model.FxSnapshot
import com.example.app_pos.network.dto.FxSnapshotDto

/**
 * Wire to domain, field for field.
 *
 * A mapper for a shape this simple earns its place the same way the others do: it is the
 * seam that lets the JSON names change without the domain hearing about it, and it keeps
 * `network.dto` out of every file that only wants a rate.
 */
fun FxSnapshotDto.toDomain(): FxSnapshot = FxSnapshot(
    // The row's OWN date, which the server may have moved back to the last reading before
    // the day asked for. Carried through rather than replaced with the request's date: the
    // screen says which day it is quoting, and it has to be the true one.
    asOf = asOf,
    usdMinor = usdMinor,
    eurMinor = eurMinor,
    goldMinor = goldMinor
)
