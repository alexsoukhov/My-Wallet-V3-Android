package piuk.blockchain.androidcore.data.payments

import com.blockchain.logging.LastTxUpdater
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.wallet.keys.SigningKey
import info.blockchain.wallet.payload.data.XPubs
import info.blockchain.wallet.payload.model.Utxo
import info.blockchain.wallet.payment.OutputType
import info.blockchain.wallet.payment.SpendableUnspentOutputs
import io.reactivex.Observable
import io.reactivex.Single
import org.bitcoinj.core.Transaction
import piuk.blockchain.androidcore.data.rxjava.RxBus
import piuk.blockchain.androidcore.data.rxjava.RxPinning
import piuk.blockchain.androidcore.utils.extensions.applySchedulers
import java.math.BigInteger

class SendDataManager(
    private val paymentService: PaymentService,
    private val lastTxUpdater: LastTxUpdater,
    rxBus: RxBus
) {
    data class MaxAvailable(
        val maxSpendable: CryptoValue,
        val feeForMax: CryptoValue
    )

    private val rxPinning: RxPinning = RxPinning(rxBus)

    /**
     * Submits a Bitcoin payment to a specified BTC address and returns the transaction hash if
     * successful
     *
     * @param unspentOutputBundle UTXO object
     * @param keys A List of elliptic curve keys
     * @param toAddress The address to send the funds to
     * @param changeAddress A change address
     * @param bigIntFee The specified fee amount
     * @param bigIntAmount The actual transaction amount
     * @return An [Observable] wrapping a [String] where the String is the transaction hash
     */
    fun submitBtcPayment(
        unspentOutputBundle: SpendableUnspentOutputs,
        keys: List<SigningKey>,
        toAddress: String,
        changeAddress: String,
        bigIntFee: BigInteger,
        bigIntAmount: BigInteger
    ): Observable<String> {
        return rxPinning.call<String> {
            paymentService.submitBtcPayment(
                unspentOutputBundle,
                keys,
                toAddress,
                changeAddress,
                bigIntFee,
                bigIntAmount
            )
        }.logLastTx()
            .applySchedulers()
    }

    fun submitBtcPayment(
        signedTx: Transaction
    ): Single<String> =
        rxPinning.callSingle<String> {
            paymentService.submitBtcPayment(
                signedTx
            )
        }.logLastTx()
            .applySchedulers()

    fun createAndSignBtcTransaction(
        unspentOutputBundle: SpendableUnspentOutputs,
        keys: List<SigningKey>,
        toAddress: String,
        changeAddress: String,
        bigIntFee: BigInteger,
        bigIntAmount: BigInteger
    ): Transaction {
        return paymentService.signAngGetBtcTx(
            unspentOutputBundle, keys, toAddress, changeAddress, bigIntFee, bigIntAmount
        )
    }

    /**
     * Submits a Bitcoin Cash payment to a specified BCH address and returns the transaction hash if
     * successful
     *
     * @param unspentOutputBundle UTXO object
     * @param keys A List of elliptic curve keys
     * @param toAddress The address to send the funds to
     * @param changeAddress A change address
     * @param bigIntFee The specified fee amount
     * @param bigIntAmount The actual transaction amount
     * @return An [Observable] wrapping a [String] where the String is the transaction hash
     */
    fun submitBchPayment(
        unspentOutputBundle: SpendableUnspentOutputs,
        keys: List<SigningKey>,
        toAddress: String,
        changeAddress: String,
        bigIntFee: BigInteger,
        bigIntAmount: BigInteger
    ): Observable<String> {

        return rxPinning.call<String> {
            paymentService.submitBchPayment(
                unspentOutputBundle,
                keys,
                toAddress,
                changeAddress,
                bigIntFee,
                bigIntAmount
            )
        }.logLastTx()
            .applySchedulers()
    }

    /**
     * Returns an [Utxo] object containing all the unspent outputs for a given
     * Account.
     *
     * @param xpubs The Bitcoin Account you wish to query
     * @return An [Observable] wrapping an [Utxo] object
     */

    fun getUnspentBtcOutputs(xpubs: XPubs): Single<List<Utxo>> =
        rxPinning.callSingle {
            paymentService.getUnspentBtcOutputs(xpubs)
        }.applySchedulers()

    /**
     * Returns an [Utxo] object containing all the unspent outputs for a given
     * Bitcoin Cash address. Please note that this method only accepts a valid Base58 (ie Legacy)
     * BCH address. BECH32 is not accepted by the endpoint.
     *
     * @param address The Bitcoin Cash address you wish to query, as a Base58 address String
     * @return An [Observable] wrapping an [Utxo] object
     */
    fun getUnspentBchOutputs(address: String): Single<List<Utxo>> =
        rxPinning.callSingle {
            paymentService.getUnspentBchOutputs(address)
        }.applySchedulers()

    /**
     * Returns a [SpendableUnspentOutputs] object from a given [Utxo] object,
     * given the payment amount and the current fee per kB. This method selects the minimum number
     * of inputs necessary to allow a successful payment by selecting from the largest inputs
     * first.
     *
     * @param unspentCoins The addresses' [Utxo]
     * @param targetOutputType Destination output type
     * @param changeOutputType Change output type
     * @param paymentAmount The amount you wish to send, as a [CryptoValue]
     * @param feePerKb The current fee per kB, as a [BigInteger]
     * an extra input and therefore affects the transaction fee.
     * @return An [SpendableUnspentOutputs] object, which wraps a list of spendable outputs
     * for the given inputs
     */
    fun getSpendableCoins(
        unspentCoins: List<Utxo>,
        targetOutputType: OutputType,
        changeOutputType: OutputType,
        paymentAmount: CryptoValue,
        feePerKb: CryptoValue
    ): SpendableUnspentOutputs = paymentService.getSpendableCoins(
        unspentCoins,
        targetOutputType,
        changeOutputType,
        paymentAmount.toBigInteger(),
        feePerKb.toBigInteger(),
        paymentAmount.currency == CryptoCurrency.BCH
    )

    /**
     * Calculates the total amount of bitcoin or bitcoin cash that can be swept from an [Utxo]
     * object and returns the amount that can be recovered, accounting for fees
     *
     * @param cryptoCurrency The currency for which you wish to calculate the max available.
     * @param unspentCoins An [Utxo] object that you wish to sweep
     * @param targetOutputType Destination output type
     * @param feePerKb The current fee per kB on the network
     * @return the sweepable amount as a CryptoValue
     */
    fun getMaximumAvailable(
        cryptoCurrency: CryptoCurrency,
        unspentCoins: List<Utxo>,
        targetOutputType: OutputType,
        feePerKb: CryptoValue
    ): MaxAvailable {
        val available = paymentService.getMaximumAvailable(
            unspentCoins,
            targetOutputType,
            feePerKb.toBigInteger(),
            cryptoCurrency == CryptoCurrency.BCH
        )

        return MaxAvailable(
            maxSpendable = CryptoValue.fromMinor(cryptoCurrency, available.first),
            feeForMax = CryptoValue.fromMinor(cryptoCurrency, available.second)
        )
    }

    /**
     * Returns true if the `absoluteFee` is adequate for the number of inputs/outputs in the
     * transaction.
     *
     * @param inputs The number of inputs
     * @param outputs List of output types (P2PKH, P2WPKH)
     * @param absoluteFee The absolute fee as a [BigInteger]
     * @return True if the fee is adequate, false if not
     */
    fun isAdequateFee(inputs: List<Utxo>, outputs: List<OutputType>, absoluteFee: BigInteger): Boolean =
        paymentService.isAdequateFee(inputs, outputs, absoluteFee)

    /**
     * Returns the estimated size of the transaction in kB.
     *
     * @param inputs The number of inputs
     * @param outputs List of output types (P2PKH, P2WPKH)
     * @return The estimated size of the transaction in kB
     */
    fun estimateSize(inputs: List<Utxo>, outputs: List<OutputType>): Double =
        paymentService.estimateSize(inputs, outputs)

    /**
     * Returns an estimated absolute fee in satoshis (as a [BigInteger] for a given number of
     * inputs and outputs.
     *
     * @param inputs The number of inputs
     * @param outputs List of output types (P2PKH, P2WPKH)
     * @param feePerKb The current fee per kB om the network
     * @return A [BigInteger] representing the absolute fee
     */
    fun estimatedFee(inputs: List<Utxo>, outputs: List<OutputType>, feePerKb: BigInteger): BigInteger =
        paymentService.estimateFee(inputs, outputs, feePerKb)

    private fun Observable<String>.logLastTx(): Observable<String> =
        this.flatMap {
            lastTxUpdater.updateLastTxTime()
                .onErrorComplete()
                .andThen(Observable.just(it))
        }

    private fun Single<String>.logLastTx(): Single<String> =
        this.flatMap {
            lastTxUpdater.updateLastTxTime()
                .onErrorComplete()
                .andThen(Single.just(it))
        }
}
