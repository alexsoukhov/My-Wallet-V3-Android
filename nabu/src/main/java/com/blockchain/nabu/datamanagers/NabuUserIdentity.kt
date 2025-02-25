package com.blockchain.nabu.datamanagers

import com.blockchain.extensions.exhaustive
import com.blockchain.nabu.Feature
import com.blockchain.nabu.Tier
import com.blockchain.nabu.UserIdentity
import com.blockchain.nabu.datamanagers.repositories.interest.InterestEligibilityProvider
import com.blockchain.nabu.models.responses.nabu.KycTierLevel
import com.blockchain.nabu.service.TierService
import io.reactivex.rxjava3.core.Single
import java.lang.IllegalArgumentException

class NabuUserIdentity(
    private val custodialWalletManager: CustodialWalletManager,
    private val interestEligibilityProvider: InterestEligibilityProvider,
    private val simpleBuyEligibilityProvider: SimpleBuyEligibilityProvider,
    private val tierService: TierService
) : UserIdentity {
    override fun isEligibleFor(feature: Feature): Single<Boolean> {
        return when (feature) {
            is Feature.TierLevel -> tierService.tiers().map {
                it.isNotInitialisedFor(feature.tier.toKycTierLevel())
            }
            is Feature.SimpleBuy -> simpleBuyEligibilityProvider.isEligibleForSimpleBuy()
            is Feature.Interest -> interestEligibilityProvider.getEligibilityForCustodialAssets()
                .map { assets -> assets.map { it.cryptoCurrency }.contains(feature.currency) }
            is Feature.SimplifiedDueDiligence -> custodialWalletManager.isSimplifiedDueDiligenceEligible()
        }.exhaustive
    }

    override fun isVerifiedFor(feature: Feature): Single<Boolean> {
        return when (feature) {
            is Feature.TierLevel -> tierService.tiers().map {
                it.isApprovedFor(feature.tier.toKycTierLevel())
            }
            is Feature.SimplifiedDueDiligence -> custodialWalletManager.fetchSimplifiedDueDiligenceUserState().map {
                it.isVerified
            }
            is Feature.SimpleBuy,
            is Feature.Interest -> throw IllegalArgumentException("Cannot be verified for $feature")
        }.exhaustive
    }

    private fun Tier.toKycTierLevel(): KycTierLevel =
        when (this) {
            Tier.BRONZE -> KycTierLevel.BRONZE
            Tier.SILVER -> KycTierLevel.SILVER
            Tier.GOLD -> KycTierLevel.GOLD
        }.exhaustive
}