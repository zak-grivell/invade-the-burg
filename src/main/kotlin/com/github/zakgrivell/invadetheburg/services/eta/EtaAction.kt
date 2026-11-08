package com.github.zakgrivell.invadetheburg.services.eta

internal enum class EtaAction {
    /** Open or keep the break exactly as without ETA. */
    SHOW_AS_TODAY,
    /** The agent should finish very soon: do not open a break for this stretch. */
    SKIP_BREAK,
    /** A break is showing and the agent is almost done. */
    WRAPPING_UP_HINT,
    /** No ETA input applies (not in a WORKING stretch); behave as without ETA. */
    NONE,
}

/** What the overlay should do for the current ETA. Unknown estimates never change today's behaviour. */
internal fun etaAction(decision: EtaDecision, breakShowing: Boolean, eta: Eta): EtaAction = when {
    eta !is Eta.Estimate -> EtaAction.SHOW_AS_TODAY
    decision == EtaDecision.SOFT_WARN -> if (breakShowing) EtaAction.WRAPPING_UP_HINT else EtaAction.SKIP_BREAK
    else -> EtaAction.SHOW_AS_TODAY
}

/** Shown instead of a hidden badge when a skipped break was reopened because no past stretch lasted this long. */
internal const val LONGER_THAN_USUAL_BADGE = "Longer than usual"

/** Badge text from the p50 estimate; null (badge hidden) when there is no estimate. */
internal fun etaBadgeText(eta: Eta, action: EtaAction): String? {
    if (eta !is Eta.Estimate) return null
    if (action == EtaAction.WRAPPING_UP_HINT) return "Wrapping up…"
    val seconds = Math.round(eta.p50)
    return if (seconds < 60) "~${maxOf(seconds, 1)} s left" else "~${(seconds + 30) / 60} min left"
}

/** [badge] is null when nothing should be shown. */
internal data class EtaView(val action: EtaAction, val badge: String?) {
    companion object { val NONE = EtaView(EtaAction.NONE, null) }
}
