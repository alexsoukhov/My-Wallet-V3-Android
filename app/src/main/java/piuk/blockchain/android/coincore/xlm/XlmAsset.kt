package piuk.blockchain.android.coincore.xlm

import com.blockchain.core.price.ExchangeRatesDataManager
import com.blockchain.core.custodial.TradingBalanceDataManager
import com.blockchain.core.interest.InterestBalanceDataManager
import com.blockchain.featureflags.InternalFeatureFlagApi
import com.blockchain.logging.CrashLogger
import com.blockchain.nabu.UserIdentity
import com.blockchain.nabu.datamanagers.CustodialWalletManager
import com.blockchain.preferences.CurrencyPrefs
import com.blockchain.preferences.WalletStatus
import com.blockchain.sunriver.StellarPayment
import com.blockchain.sunriver.XlmDataManager
import com.blockchain.sunriver.XlmFeesFetcher
import com.blockchain.sunriver.fromStellarUri
import com.blockchain.sunriver.isValidXlmQr
import com.blockchain.wallet.DefaultLabels
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.isCustodialOnly
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import piuk.blockchain.android.coincore.CryptoAddress
import piuk.blockchain.android.coincore.ReceiveAddress
import piuk.blockchain.android.coincore.SingleAccountList
import piuk.blockchain.android.coincore.TxResult
import piuk.blockchain.android.coincore.impl.CryptoAssetBase
import piuk.blockchain.android.coincore.impl.CustodialTradingAccount
import piuk.blockchain.android.thepit.PitLinking
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.data.walletoptions.WalletOptionsDataManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal class XlmAsset(
    payloadManager: PayloadDataManager,
    private val xlmDataManager: XlmDataManager,
    private val xlmFeesFetcher: XlmFeesFetcher,
    private val walletOptionsDataManager: WalletOptionsDataManager,
    custodialManager: CustodialWalletManager,
    interestBalances: InterestBalanceDataManager,
    tradingBalances: TradingBalanceDataManager,
    exchangeRates: ExchangeRatesDataManager,
    currencyPrefs: CurrencyPrefs,
    labels: DefaultLabels,
    pitLinking: PitLinking,
    crashLogger: CrashLogger,
    private val walletPreferences: WalletStatus,
    identity: UserIdentity,
    features: InternalFeatureFlagApi
) : CryptoAssetBase(
    payloadManager,
    exchangeRates,
    currencyPrefs,
    labels,
    custodialManager,
    interestBalances,
    tradingBalances,
    pitLinking,
    crashLogger,
    identity,
    features
) {

    override val asset: AssetInfo
        get() = CryptoCurrency.XLM

    override val isCustodialOnly: Boolean = asset.isCustodialOnly
    override val multiWallet: Boolean = false

    override fun initToken(): Completable =
        Completable.complete()

    override fun loadNonCustodialAccounts(labels: DefaultLabels): Single<SingleAccountList> =
        xlmDataManager.defaultAccount()
            .map {
                XlmCryptoWalletAccount(
                    payloadManager = payloadManager,
                    xlmAccountReference = it,
                    xlmManager = xlmDataManager,
                    exchangeRates = exchangeRates,
                    xlmFeesFetcher = xlmFeesFetcher,
                    walletOptionsDataManager = walletOptionsDataManager,
                    walletPreferences = walletPreferences,
                    custodialWalletManager = custodialManager,
                    identity = identity
                )
            }.map {
                listOf(it)
            }

    override fun loadCustodialAccounts(): Single<SingleAccountList> =
        Single.just(
            listOf(
                CustodialTradingAccount(
                    asset = asset,
                    label = labels.getDefaultCustodialWalletLabel(),
                    exchangeRates = exchangeRates,
                    custodialWalletManager = custodialManager,
                    tradingBalances = tradingBalances,
                    identity = identity,
                    features = features
                )
            )
        )

    override fun parseAddress(address: String, label: String?): Maybe<ReceiveAddress> =
        Maybe.fromCallable {
            if (address.isValidXlmQr()) {
                val payment = address.fromStellarUri()
                XlmAddress(
                    _address = payment.public.accountId,
                    stellarPayment = payment
                )
            } else {
                if (isValidAddress(address)) {
                    XlmAddress(address, label ?: address)
                } else {
                    null
                }
            }
        }

    override fun isValidAddress(address: String): Boolean =
        xlmDataManager.isAddressValid(address)
}

internal class XlmAddress(
    _address: String,
    _label: String? = null,
    val stellarPayment: StellarPayment? = null,
    override val onTxCompleted: (TxResult) -> Completable = { Completable.complete() }
) : CryptoAddress {

    private val parts = _address.split(":")
    override val label: String = _label ?: address

    override val address: String
        get() = parts[0]

    override val memo: String?
        get() = parts.takeIf { it.size > 1 }?.get(1)

    override val asset: AssetInfo = CryptoCurrency.XLM

    override fun equals(other: Any?): Boolean {
        return (other is XlmAddress) &&
            (other.asset == asset && other.address == address && other.label == label)
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + (stellarPayment?.hashCode() ?: 0)
        result = 31 * result + asset.hashCode()
        return result
    }

    override fun toUrl(amount: CryptoValue): String {
        val root = "web+stellar:pay?destination=$address"
        val memo = memo?.let {
            "&memo=${URLEncoder.encode(memo, StandardCharsets.UTF_8.name())}&memo_type=MEMO_TEXT"
        } ?: ""
        val value = amount.takeIf { it.isPositive }?.let { "&amount=${amount.toStringWithoutSymbol()}" } ?: ""

        return root + memo + value
    }
}
