package com.github.zakgrivell.invadetheburg.services.eta

internal fun decide(eta: Eta): EtaDecision = when (eta) {
    is Eta.Estimate -> when {
        eta.p20 > 60.0 -> EtaDecision.START_BREAK
        eta.p20 < 30.0 -> EtaDecision.SOFT_WARN
        else -> EtaDecision.HOLD
    }
    Eta.Unknown -> EtaDecision.HOLD
}
