package com.example.app_pos.network.mapper

import com.example.app_pos.model.PgwJob
import com.example.app_pos.model.PgwJobKind
import com.example.app_pos.network.dto.PgwJobDto

/**
 * PGW job wire → domain.
 *
 * A job whose `kind` this build does not recognise is DROPPED rather than guessed at. The
 * two kinds do opposite things — one prints a slip for money already booked, the other
 * charges a card for money not yet taken — so a wrong guess is worse than not acting. An
 * unknown job simply stays pending on the server until a build that understands it polls.
 */
fun PgwJobDto.toDomainOrNull(): PgwJob? {
    val parsedKind = PgwJobKind.entries.firstOrNull { it.name == kind } ?: return null
    return PgwJob(
        jobId = jobId,
        kind = parsedKind,
        customerId = customerId,
        amountMinor = amountMinor,
        orderBody = orderBody
    )
}

/** Drops the jobs this build cannot act on; see [toDomainOrNull]. */
fun List<PgwJobDto>.toDomainList(): List<PgwJob> = mapNotNull { it.toDomainOrNull() }
