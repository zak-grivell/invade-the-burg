package com.github.zakgrivell.invadetheburg.services.eta

// TEMP(agent-eta): placeholder until Lane B's EtaPredictor merges. Then pass `{ EtaPredictor(it) }` as the
// predictorFactory in EtaMonitor.createDefault() and delete this file.
internal object StubEtaPredictor : EtaPredictorApi {
    override fun predict(f: EtaFeatures): Eta = Eta.Unknown
}
