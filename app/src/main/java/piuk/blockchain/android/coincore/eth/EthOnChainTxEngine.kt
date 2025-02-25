package piuk.blockchain.android.coincore.eth

import com.blockchain.nabu.datamanagers.TransactionError
import com.blockchain.preferences.WalletStatus
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.Money
import info.blockchain.balance.Money.Companion.max
import info.blockchain.wallet.api.data.FeeOptions
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.kotlin.Singles
import org.web3j.crypto.RawTransaction
import org.web3j.utils.Convert
import piuk.blockchain.android.coincore.AssetAction
import piuk.blockchain.android.coincore.CryptoAddress
import piuk.blockchain.android.coincore.FeeLevel
import piuk.blockchain.android.coincore.FeeSelection
import piuk.blockchain.android.coincore.PendingTx
import piuk.blockchain.android.coincore.TxConfirmation
import piuk.blockchain.android.coincore.TxConfirmationValue
import piuk.blockchain.android.coincore.TxResult
import piuk.blockchain.android.coincore.TxValidationFailure
import piuk.blockchain.android.coincore.ValidationState
import piuk.blockchain.android.coincore.impl.txEngine.OnChainTxEngineBase
import piuk.blockchain.android.coincore.toUserFiat
import piuk.blockchain.android.coincore.updateTxValidity
import piuk.blockchain.android.ui.transactionflow.flow.FeeInfo
import piuk.blockchain.androidcore.data.ethereum.EthDataManager
import piuk.blockchain.androidcore.data.fees.FeeDataManager
import piuk.blockchain.androidcore.utils.extensions.then
import java.math.BigDecimal
import java.math.BigInteger

open class EthOnChainTxEngine(
    private val ethDataManager: EthDataManager,
    private val feeManager: FeeDataManager,
    walletPreferences: WalletStatus,
    requireSecondPassword: Boolean
) : OnChainTxEngineBase(
    requireSecondPassword,
    walletPreferences
) {

    override fun assertInputsValid() {
        check(txTarget is CryptoAddress)
        check((txTarget as CryptoAddress).asset == CryptoCurrency.ETHER)
        check(sourceAsset == CryptoCurrency.ETHER)
    }

    override fun doInitialiseTx(): Single<PendingTx> =
        Single.just(
            PendingTx(
                amount = CryptoValue.zero(sourceAsset),
                totalBalance = CryptoValue.zero(sourceAsset),
                availableBalance = CryptoValue.zero(sourceAsset),
                feeForFullAvailable = CryptoValue.zero(sourceAsset),
                feeAmount = CryptoValue.zero(sourceAsset),
                feeSelection = FeeSelection(
                    selectedLevel = mapSavedFeeToFeeLevel(fetchDefaultFeeLevel(sourceAsset)),
                    availableLevels = AVAILABLE_FEE_LEVELS,
                    asset = sourceAsset
                ),
                selectedFiat = userFiat
            )
        )

    override fun doBuildConfirmations(pendingTx: PendingTx): Single<PendingTx> =
        Single.just(
            pendingTx.copy(
                confirmations = listOfNotNull(
                    TxConfirmationValue.From(sourceAccount, sourceAsset),
                    TxConfirmationValue.To(
                        txTarget, AssetAction.Send, sourceAccount
                    ),
                    TxConfirmationValue.CompoundNetworkFee(
                        sendingFeeInfo = if (!pendingTx.feeAmount.isZero) {
                            FeeInfo(
                                pendingTx.feeAmount,
                                pendingTx.feeAmount.toUserFiat(exchangeRates),
                                sourceAsset
                            )
                        } else null,
                        feeLevel = pendingTx.feeSelection.selectedLevel
                    ),
                    TxConfirmationValue.Total(
                        totalWithFee = (pendingTx.amount as CryptoValue).plus(
                            pendingTx.feeAmount as CryptoValue
                        ),
                        exchange = pendingTx.amount.toUserFiat(exchangeRates)
                            .plus(pendingTx.feeAmount.toUserFiat(exchangeRates))
                    ),
                    TxConfirmationValue.Description()
                )
            )
        )

    private fun absoluteFee(feeLevel: FeeLevel): Single<CryptoValue> =
        feeOptions().map {
            CryptoValue.fromMinor(
                sourceAsset,
                Convert.toWei(
                    BigDecimal.valueOf(it.gasLimit * it.mapFeeLevel(feeLevel)),
                    Convert.Unit.GWEI
                )
            )
        }

    private fun FeeOptions.mapFeeLevel(feeLevel: FeeLevel) =
        when (feeLevel) {
            FeeLevel.None -> 0L
            FeeLevel.Regular -> regularFee
            FeeLevel.Priority -> priorityFee
            FeeLevel.Custom -> priorityFee
        }

    private fun feeOptions(): Single<FeeOptions> =
        feeManager.ethFeeOptions.singleOrError()

    override fun doUpdateAmount(amount: Money, pendingTx: PendingTx): Single<PendingTx> {
        require(amount is CryptoValue)
        require(amount.currency == sourceAsset)

        return Singles.zip(
            sourceAccount.accountBalance.map { it as CryptoValue },
            sourceAccount.actionableBalance.map { it as CryptoValue },
            absoluteFee(pendingTx.feeSelection.selectedLevel)
        ) { total, available, fees ->
            pendingTx.copy(
                amount = amount,
                totalBalance = total,
                availableBalance = max(available - fees, CryptoValue.zero(sourceAsset)) as CryptoValue,
                feeForFullAvailable = fees,
                feeAmount = fees
            )
        }
    }

    override fun doValidateAmount(pendingTx: PendingTx): Single<PendingTx> =
        validateAmounts(pendingTx)
            .then { validateSufficientFunds(pendingTx) }
            .updateTxValidity(pendingTx)

    // We can make some assumptions here over the previous impl;
    // 1. a CryptAddress object will be self-validating, so we need not check that it's valid
    override fun doValidateAll(pendingTx: PendingTx): Single<PendingTx> =
        validateAmounts(pendingTx)
            .then { validateSufficientFunds(pendingTx) }
            .then { validateNoPendingTx() }
            .updateTxValidity(pendingTx)

    override fun doExecute(pendingTx: PendingTx, secondPassword: String): Single<TxResult> =
        createTransaction(pendingTx)
            .flatMap {
                ethDataManager.signEthTransaction(it, secondPassword)
            }
            .flatMap { ethDataManager.pushTx(it) }
            .flatMap { hash ->
                pendingTx.getOption<TxConfirmationValue.Description>(TxConfirmation.DESCRIPTION)?.let { notes ->
                    ethDataManager.updateTransactionNotes(hash, notes.text)
                }?.toSingle {
                    hash
                } ?: Single.just(hash)
            }.onErrorResumeNext {
                Single.error(TransactionError.ExecutionFailed)
            }.map {
                TxResult.HashedTxResult(it, pendingTx.amount)
            }

    private fun createTransaction(pendingTx: PendingTx): Single<RawTransaction> {
        val targetAddress = txTarget as CryptoAddress

        return Singles.zip(
            ethDataManager.getNonce(),
            ethDataManager.isContractAddress(targetAddress.address),
            feeOptions()
        ).map { (nonce, isContract, fees) ->
            ethDataManager.createEthTransaction(
                nonce = nonce,
                to = targetAddress.address,
                gasPriceWei = fees.gasPrice(pendingTx.feeSelection.selectedLevel),
                gasLimitGwei = fees.getGasLimit(isContract),
                weiValue = pendingTx.amount.toBigInteger()
            )
        }
    }

    // TODO: Have FeeOptions deal with this conversion
    private fun FeeOptions.gasPrice(feeLevel: FeeLevel): BigInteger =
        Convert.toWei(
            BigDecimal.valueOf(this.mapFeeLevel(feeLevel)),
            Convert.Unit.GWEI
        ).toBigInteger()

    private fun FeeOptions.getGasLimit(isContract: Boolean): BigInteger =
        BigInteger.valueOf(
            if (isContract) gasLimitContract else gasLimit
        )

    private fun validateAmounts(pendingTx: PendingTx): Completable =
        Completable.fromCallable {
            if (pendingTx.amount <= CryptoValue.zero(sourceAsset)) {
                throw TxValidationFailure(ValidationState.INVALID_AMOUNT)
            }
        }

    private fun validateSufficientFunds(pendingTx: PendingTx): Completable =
        Singles.zip(
            sourceAccount.actionableBalance,
            absoluteFee(pendingTx.feeSelection.selectedLevel)
        ) { balance: Money, fee: Money ->
            if (fee + pendingTx.amount > balance) {
                throw TxValidationFailure(ValidationState.INSUFFICIENT_FUNDS)
            } else {
                true
            }
        }.ignoreElement()

    private fun validateNoPendingTx() =
        ethDataManager.isLastTxPending()
            .flatMapCompletable { hasUnconfirmed: Boolean ->
                if (hasUnconfirmed) {
                    Completable.error(TxValidationFailure(ValidationState.HAS_TX_IN_FLIGHT))
                } else {
                    Completable.complete()
                }
            }

    companion object {
        private val AVAILABLE_FEE_LEVELS = setOf(FeeLevel.Regular, FeeLevel.Priority)
    }
}
