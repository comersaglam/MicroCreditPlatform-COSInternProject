package com.example.app_pos.model

/**
 * Where a raised approval has got to.
 *
 * The terminal waits on this while the customer decides, and the sale's ending depends on
 * which one comes back: an approved request closes the sale and lets the payment gateway
 * print, a rejected one cancels it. The gateway is holding a receipt either way, so
 * "still waiting" must stay distinct from both — reporting a decision early would print a
 * slip for a debt nobody agreed to.
 */
enum class ApprovalStatus { PENDING, APPROVED, REJECTED }

/**
 * Which kind of device raised an approval.
 *
 * Not cosmetic: the server reads it to decide whether to leave gateway work behind. A
 * terminal is already standing in front of the PGW and fires its own intent, so a job
 * queued for it would hand the same receipt over twice; a phone has nobody there, so the
 * work has to wait for a terminal to collect it.
 */
const val ORIGIN_POS: String = "POS"

/** Raised on a phone — the server queues the gateway half for a terminal to collect. */
const val ORIGIN_PHONE: String = "PHONE"

/**
 * Which side of an account a request is addressed to.
 *
 * One account holds both roles, so "what is waiting on me" is really two inboxes. A
 * terminal asks for [ROLE_SELLER] only: the till is a shop tool, and its owner's personal
 * business at another shop belongs on their phone — the POS has no screen that could even
 * show the result of answering it.
 */
const val ROLE_SELLER: String = "SELLER"

/** The other half: somebody else's shop asking to book something on you. */
const val ROLE_BUYER: String = "BUYER"

