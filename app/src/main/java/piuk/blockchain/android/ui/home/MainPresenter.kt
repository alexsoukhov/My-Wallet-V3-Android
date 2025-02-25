package piuk.blockchain.android.ui.home

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import com.blockchain.extensions.exhaustive
import com.blockchain.extensions.valueOf
import com.blockchain.logging.CrashLogger
import com.blockchain.nabu.datamanagers.CustodialWalletManager
import com.blockchain.nabu.datamanagers.OrderState
import com.blockchain.nabu.models.data.BankTransferDetails
import com.blockchain.nabu.models.data.BankTransferStatus
import com.blockchain.nabu.models.responses.nabu.CampaignData
import com.blockchain.nabu.models.responses.nabu.KycState
import com.blockchain.nabu.models.responses.nabu.NabuApiException
import com.blockchain.nabu.models.responses.nabu.NabuErrorCodes
import com.blockchain.notifications.analytics.Analytics
import com.blockchain.notifications.analytics.AnalyticsEvents
import com.blockchain.preferences.BankLinkingPrefs
import com.blockchain.sunriver.XlmDataManager
import com.google.gson.JsonSyntaxException
import info.blockchain.balance.FiatValue
import info.blockchain.wallet.payload.PayloadManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.schedulers.Schedulers
import piuk.blockchain.android.BuildConfig
import piuk.blockchain.android.R
import piuk.blockchain.android.campaign.CampaignType
import piuk.blockchain.android.campaign.SunriverCampaignRegistration
import piuk.blockchain.android.campaign.SunriverCardType
import piuk.blockchain.android.coincore.AssetAction
import piuk.blockchain.android.coincore.BlockchainAccount
import piuk.blockchain.android.coincore.CryptoTarget
import piuk.blockchain.android.deeplink.DeepLinkProcessor
import piuk.blockchain.android.deeplink.EmailVerifiedLinkState
import piuk.blockchain.android.deeplink.LinkState
import piuk.blockchain.android.deeplink.OpenBankingLinkType
import piuk.blockchain.android.kyc.KycLinkState
import piuk.blockchain.android.networking.PollResult
import piuk.blockchain.android.networking.PollService
import piuk.blockchain.android.scan.QrScanError
import piuk.blockchain.android.scan.QrScanResultProcessor
import piuk.blockchain.android.scan.ScanResult
import piuk.blockchain.android.simplebuy.SimpleBuyState
import piuk.blockchain.android.simplebuy.SimpleBuySyncFactory
import piuk.blockchain.android.sunriver.CampaignLinkState
import piuk.blockchain.android.thepit.PitLinking
import piuk.blockchain.android.ui.auth.newlogin.SecureChannelManager
import piuk.blockchain.android.ui.base.MvpPresenter
import piuk.blockchain.android.ui.base.MvpView
import piuk.blockchain.android.ui.kyc.settings.KycStatusHelper
import piuk.blockchain.android.ui.linkbank.BankAuthDeepLinkState
import piuk.blockchain.android.ui.linkbank.BankAuthFlowState
import piuk.blockchain.android.ui.linkbank.BankPaymentApproval
import piuk.blockchain.android.ui.linkbank.fromPreferencesValue
import piuk.blockchain.android.ui.linkbank.toPreferencesValue
import piuk.blockchain.android.ui.upsell.KycUpgradePromptManager
import piuk.blockchain.androidcore.data.access.AccessState
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.utils.PersistentPrefs
import com.blockchain.notifications.analytics.Logging
import com.blockchain.notifications.analytics.secondPasswordEvent
import com.blockchain.utils.capitalizeFirstChar
import piuk.blockchain.android.deeplink.BlockchainLinkState
import piuk.blockchain.android.ui.sell.BuySellFragment
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

interface MainView : MvpView, HomeNavigator {
    fun refreshAnnouncements()
    fun kickToLauncherPage()
    fun showProgressDialog(@StringRes message: Int)
    fun hideProgressDialog()
    fun clearAllDynamicShortcuts()
    fun showHomebrewDebugMenu()
    fun enableSwapButton(isEnabled: Boolean)
    fun shouldIgnoreDeepLinking(): Boolean
    fun displayDialog(@StringRes title: Int, @StringRes message: Int)

    fun startTransactionFlowWithTarget(targets: Collection<CryptoTarget>)
    fun showScanTargetError(error: QrScanError)
    fun showOpenBankingDeepLinkError()

    fun handlePaymentForCancelledOrder(state: SimpleBuyState)
    fun handleApprovalDepositComplete(orderValue: FiatValue, estimatedTransactionCompletionTime: String)
    fun handleApprovalDepositInProgress(amount: FiatValue)
    fun handleApprovalDepositError(currency: String)
    fun handleApprovalDepositTimeout(currencyCode: String)
    fun handleBuyApprovalError()

    fun launchUpsellAssetAction(upsell: KycUpgradePromptManager.Type, action: AssetAction, account: BlockchainAccount)
    fun launchAssetAction(action: AssetAction, account: BlockchainAccount? = null)
}

class MainPresenter internal constructor(
    private val prefs: PersistentPrefs,
    private val accessState: AccessState,
    private val payloadDataManager: PayloadDataManager,
    private val qrProcessor: QrScanResultProcessor,
    private val kycStatusHelper: KycStatusHelper,
    private val deepLinkProcessor: DeepLinkProcessor,
    private val sunriverCampaignRegistration: SunriverCampaignRegistration,
    private val xlmDataManager: XlmDataManager,
    private val pitLinking: PitLinking,
    private val simpleBuySync: SimpleBuySyncFactory,
    private val crashLogger: CrashLogger,
    private val analytics: Analytics,
    private val credentialsWiper: CredentialsWiper,
    private val bankLinkingPrefs: BankLinkingPrefs,
    private val custodialWalletManager: CustodialWalletManager,
    private val upsellManager: KycUpgradePromptManager,
    val payloadManager: PayloadManager,
    private val secureChannelManager: SecureChannelManager
) : MvpPresenter<MainView>() {

    override val alwaysDisableScreenshots: Boolean = false
    override val enableLogoutTimer: Boolean = true

    override fun onViewAttached() {
        if (!accessState.isLoggedIn) {
            // This should never happen, but handle the scenario anyway by starting the launcher
            // activity, which handles all login/auth/corruption scenarios itself
            view?.kickToLauncherPage()
        } else {
            logEvents()
            lightSimpleBuySync()
            doPushNotifications()
        }
    }

    override fun onViewDetached() {}

    /**
     * Initial setup of push notifications. We don't subscribe to addresses for notifications when
     * creating a new wallet. To accommodate existing wallets we need subscribe to the next
     * available addresses.
     */
    private fun doPushNotifications() {
        if (prefs.arePushNotificationsEnabled) {
            compositeDisposable += payloadDataManager.syncPayloadAndPublicKeys()
                .subscribe({ /*no-op*/ },
                    { throwable -> Timber.e(throwable) })
        }
    }

    private fun checkKycStatus() {
        compositeDisposable += kycStatusHelper.shouldDisplayKyc()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { view?.enableSwapButton(it) },
                { Timber.e(it) }
            )
    }

    private fun setDebugExchangeVisibility() {
        if (BuildConfig.DEBUG) {
            view?.showHomebrewDebugMenu()
        }
    }

    private fun lightSimpleBuySync() {
        compositeDisposable += simpleBuySync.lightweightSync()
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                view?.showProgressDialog(R.string.please_wait)
            }
            .doAfterTerminate {
                view?.hideProgressDialog()

                val strUri = prefs.getValue(PersistentPrefs.KEY_SCHEME_URL, "")
                if (strUri.isNotEmpty()) {
                    prefs.removeValue(PersistentPrefs.KEY_SCHEME_URL)
                    processScanResult(strUri)
                }
                view?.refreshAnnouncements()
            }
            .subscribeBy(
                onComplete = {
                    checkKycStatus()
                    setDebugExchangeVisibility()
                },
                onError = { throwable ->
                    logException(throwable)
                }
            )
    }

    private fun handlePossibleDeepLink(url: String) {
        try {
            val link = Uri.parse(url).getQueryParameter("link") ?: return
            compositeDisposable += deepLinkProcessor.getLink(link)
                .subscribeBy(
                    onError = { Timber.e(it) },
                    onSuccess = { dispatchDeepLink(it) }
                )
        } catch (t: Throwable) {
            Timber.d("Invalid link cannot be processed - ignoring")
        }
    }

    fun checkForPendingLinks(intent: Intent) {
        compositeDisposable += deepLinkProcessor.getLink(intent)
            .filter { view?.shouldIgnoreDeepLinking() == false }
            .subscribeBy(
                onError = { Timber.e(it) },
                onSuccess = { dispatchDeepLink(it) }
            )
    }

    private fun dispatchDeepLink(linkState: LinkState) {
        when (linkState) {
            is LinkState.SunriverDeepLink -> handleSunriverDeepLink(linkState)
            is LinkState.EmailVerifiedDeepLink -> handleEmailVerifiedDeepLink(linkState)
            is LinkState.KycDeepLink -> handleKycDeepLink(linkState)
            is LinkState.ThePitDeepLink -> handleThePitDeepLink(linkState)
            is LinkState.OpenBankingLink -> handleOpenBankingDeepLink(linkState)
            is LinkState.BlockchainLink -> handleBlockchainDeepLink(linkState)
            else -> {
            }
        }
    }

    private fun handleSunriverDeepLink(linkState: LinkState.SunriverDeepLink) {
        when (linkState.link) {
            is CampaignLinkState.WrongUri -> view?.displayDialog(
                R.string.sunriver_invalid_url_title,
                R.string.sunriver_invalid_url_message
            )
            is CampaignLinkState.Data -> registerForCampaign(linkState.link.campaignData)
            else -> {
            }
        }
    }

    private fun handleKycDeepLink(linkState: LinkState.KycDeepLink) {
        when (linkState.link) {
            is KycLinkState.Resubmit -> view?.launchKyc(CampaignType.Resubmission)
            is KycLinkState.EmailVerified -> view?.launchKyc(CampaignType.None)
            is KycLinkState.General -> {
                val data = linkState.link.campaignData
                if (data != null) {
                    registerForCampaign(data)
                } else {
                    view?.launchKyc(CampaignType.None)
                }
            }
            else -> {
            }
        }
    }

    private fun handleThePitDeepLink(linkState: LinkState.ThePitDeepLink) {
        view?.launchThePitLinking(linkState.linkId)
    }

    private fun handleOpenBankingDeepLink(state: LinkState.OpenBankingLink) =
        when (state.type) {
            OpenBankingLinkType.LINK_BANK -> handleBankLinking(state.consentToken)
            OpenBankingLinkType.PAYMENT_APPROVAL -> handleBankApproval(state.consentToken)
            OpenBankingLinkType.UNKNOWN -> view?.showOpenBankingDeepLinkError()
        }

    private fun handleBankApproval(consentToken: String) {
        val deepLinkState = bankLinkingPrefs.getBankLinkingState().fromPreferencesValue()

        if (deepLinkState.bankAuthFlow == BankAuthFlowState.BANK_APPROVAL_COMPLETE) {
            deepLinkState.copy(bankAuthFlow = BankAuthFlowState.NONE, bankPaymentData = null, bankLinkingInfo = null)
                .toPreferencesValue()
            return
        }

        compositeDisposable += custodialWalletManager.updateOpenBankingConsent(
            bankLinkingPrefs.getDynamicOneTimeTokenUrl(), consentToken
        ).observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onComplete = {
                    if (deepLinkState.bankAuthFlow == BankAuthFlowState.BANK_APPROVAL_PENDING) {
                        deepLinkState.bankPaymentData?.let { paymentData ->
                            handleDepositApproval(paymentData, deepLinkState)
                        } ?: handleSimpleBuyApproval()
                    }
                },
                onError = {
                    Timber.e("Error updating consent token on approval: $it")

                    resetLocalBankAuthState()

                    deepLinkState.bankPaymentData?.let { data ->
                        view?.handleApprovalDepositError(data.orderValue.currencyCode)
                    } ?: view?.handleBuyApprovalError()
                }
            )
    }

    private fun handleDepositApproval(
        paymentData: BankPaymentApproval,
        deepLinkState: BankAuthDeepLinkState
    ) {
        compositeDisposable += PollService(
            custodialWalletManager.getBankTransferCharge(paymentData.paymentId)
        ) { transferDetails ->
            transferDetails.status != BankTransferStatus.PENDING
        }.start()
            .observeOn(AndroidSchedulers.mainThread())
            .doOnSubscribe {
                view?.handleApprovalDepositInProgress(paymentData.orderValue)
            }.subscribeBy(
                onSuccess = {
                    when (it) {
                        is PollResult.FinalResult -> {
                            bankLinkingPrefs.setBankLinkingState(
                                deepLinkState.copy(
                                    bankAuthFlow = BankAuthFlowState.BANK_APPROVAL_COMPLETE,
                                    bankPaymentData = null,
                                    bankLinkingInfo = null
                                ).toPreferencesValue()
                            )

                            handleTransferStatus(it.value, paymentData)
                        }
                        is PollResult.TimeOut -> {
                            view?.handleApprovalDepositTimeout(paymentData.orderValue.currencyCode)
                        }
                        is PollResult.Cancel -> {
                            // do nothing
                        }
                        else -> {
                            // do nothing
                        }
                    }
                },
                onError = {
                    resetLocalBankAuthState()
                    view?.handleApprovalDepositError(paymentData.orderValue.currencyCode)
                }
            )
    }

    private fun handleSimpleBuyApproval() {
        simpleBuySync.currentState()?.let {
            handleOrderState(it)
        } ?: kotlin.run {
            // try to sync with server once, otherwise fail
            simpleBuySync.performSync()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeBy(
                    onComplete = {
                        simpleBuySync.currentState()?.let {
                            handleOrderState(it)
                        } ?: view?.handleBuyApprovalError()
                    }, onError = {
                        Timber.e("Error doing SB sync for bank linking $it")
                        resetLocalBankAuthState()
                        view?.handleBuyApprovalError()
                    }
                )
        }
    }

    private fun resetLocalBankAuthState() =
        bankLinkingPrefs.setBankLinkingState(
            BankAuthDeepLinkState(bankAuthFlow = BankAuthFlowState.NONE, bankPaymentData = null, bankLinkingInfo = null)
                .toPreferencesValue()
        )

    private fun handleOrderState(state: SimpleBuyState) {
        if (state.orderState == OrderState.AWAITING_FUNDS) {
            view?.launchSimpleBuyFromDeepLinkApproval()
        } else {
            resetLocalBankAuthState()
            view?.handlePaymentForCancelledOrder(state)
        }
    }

    private fun handleTransferStatus(
        it: BankTransferDetails,
        paymentData: BankPaymentApproval
    ) {
        when (it.status) {
            BankTransferStatus.COMPLETE -> {
                view?.handleApprovalDepositComplete(it.amount, getEstimatedDepositCompletionTime())
            }
            BankTransferStatus.PENDING -> {
                view?.handleApprovalDepositTimeout(paymentData.orderValue.currencyCode)
            }
            BankTransferStatus.ERROR,
            BankTransferStatus.UNKNOWN -> {
                view?.handleApprovalDepositError(paymentData.orderValue.currencyCode)
            }
        }
    }

    private fun getEstimatedDepositCompletionTime(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 3)
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        return sdf.format(cal.time)
    }

    private fun handleBankLinking(consentToken: String) {
        val linkingState = bankLinkingPrefs.getBankLinkingState().fromPreferencesValue()

        if (linkingState.bankAuthFlow == BankAuthFlowState.BANK_LINK_COMPLETE) {
            resetLocalBankAuthState()
            return
        }

        compositeDisposable += custodialWalletManager.updateOpenBankingConsent(
            bankLinkingPrefs.getDynamicOneTimeTokenUrl(), consentToken
        )
            .subscribeBy(
                onComplete = {
                    try {
                        bankLinkingPrefs.setBankLinkingState(
                            linkingState.copy(bankAuthFlow = BankAuthFlowState.BANK_LINK_COMPLETE).toPreferencesValue()
                        )

                        linkingState.bankLinkingInfo?.let {
                            view?.launchOpenBankingLinking(it)
                        }
                    } catch (e: JsonSyntaxException) {
                        view?.showOpenBankingDeepLinkError()
                    }
                },
                onError = {
                    Timber.e("Error updating consent token on new bank link: $it")

                    resetLocalBankAuthState()

                    linkingState.bankLinkingInfo?.let {
                        view?.launchOpenBankingLinking(it)
                    } ?: view?.showOpenBankingDeepLinkError()
                }
            )
    }

    private fun handleEmailVerifiedDeepLink(linkState: LinkState.EmailVerifiedDeepLink) {
        if (linkState.link === EmailVerifiedLinkState.FromPitLinking) {
            showThePitOrPitLinkingView(prefs.pitToWalletLinkId)
        }
    }

    private fun registerForCampaign(data: CampaignData) {
        compositeDisposable +=
            xlmDataManager.defaultAccount()
                .flatMapCompletable {
                    sunriverCampaignRegistration
                        .registerCampaign(data)
                }
                .andThen(kycStatusHelper.getKycStatus())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { view?.showProgressDialog(R.string.please_wait) }
                .doOnEvent { _, _ -> view?.hideProgressDialog() }
                .subscribe({ status ->
                    prefs.setValue(SunriverCardType.JoinWaitList.javaClass.simpleName, true)
                    if (status != KycState.Verified) {
                        view?.launchKyc(CampaignType.Sunriver)
                    }
                }, { throwable ->
                    Timber.e(throwable)
                    if (throwable is NabuApiException) {
                        val errorMessageStringId =
                            when (val errorCode = throwable.getErrorCode()) {
                                NabuErrorCodes.InvalidCampaignUser ->
                                    R.string.sunriver_invalid_campaign_user
                                NabuErrorCodes.CampaignUserAlreadyRegistered ->
                                    R.string.sunriver_user_already_registered
                                NabuErrorCodes.CampaignExpired ->
                                    R.string.sunriver_campaign_expired
                                else -> {
                                    Timber.e("Unknown server error $errorCode ${errorCode.code}")
                                    R.string.sunriver_generic_error
                                }
                            }
                        view?.displayDialog(
                            R.string.sunriver_invalid_url_title,
                            errorMessageStringId
                        )
                    }
                }
                )
    }

    private fun logException(throwable: Throwable) {
        crashLogger.logException(throwable)
    }

    internal fun unPair() {
        view?.clearAllDynamicShortcuts()
        credentialsWiper.wipe()
    }

    private fun logEvents() {
        analytics.logEventOnce(AnalyticsEvents.WalletSignupFirstLogIn)
        Logging.logEvent(secondPasswordEvent(payloadDataManager.isDoubleEncrypted))
    }

    internal fun clearLoginState() {
        accessState.logout()
    }

    fun onThePitMenuClicked() {
        showThePitOrPitLinkingView("")
    }

    private fun showThePitOrPitLinkingView(linkId: String) {
        compositeDisposable += pitLinking.isPitLinked().observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(onError = { Timber.e(it) }, onSuccess = { isLinked ->
                if (isLinked) {
                    view?.launchThePit()
                } else {
                    view?.launchThePitLinking(linkId)
                }
            })
    }

    fun processScanResult(scanData: String) {
        compositeDisposable += qrProcessor.processScan(scanData)
            .subscribeBy(
                onSuccess = {
                    when (it) {
                        is ScanResult.HttpUri -> handlePossibleDeepLink(scanData)
                        is ScanResult.TxTarget -> {
                            view?.startTransactionFlowWithTarget(it.targets)
                        }
                        is ScanResult.ImportedWallet -> {
                        } // TODO: as part of Auth
                        is ScanResult.SecuredChannelLogin -> secureChannelManager.sendHandshake(it.handshake)
                    }.exhaustive
                },
                onError = {
                    when (it) {
                        is QrScanError -> view?.showScanTargetError(it)
                        else -> {
                            Timber.d("Scan failed")
                        }
                    }
                }
            )
    }

    fun validateAccountAction(action: AssetAction, account: BlockchainAccount) {
        compositeDisposable += upsellManager.queryUpsell(action, account)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onSuccess = { upsell ->
                    if (upsell != KycUpgradePromptManager.Type.NONE) {
                        view?.launchUpsellAssetAction(upsell, action, account)
                    } else {
                        view?.launchAssetAction(action, account)
                    }
                },
                onError = {
                    Timber.e("Upsell manager failure")
                }
            )
    }

    private fun handleBlockchainDeepLink(linkState: LinkState.BlockchainLink) {
        when (val link = linkState.link) {
            BlockchainLinkState.NoUri -> Timber.e("Invalid deep link")
            BlockchainLinkState.Swap -> view?.launchSwap()
            BlockchainLinkState.TwoFa -> view?.launchSetup2Fa()
            BlockchainLinkState.VerifyEmail -> view?.launchVerifyEmail()
            BlockchainLinkState.SetupFingerprint -> view?.launchSetupFingerprintLogin()
            BlockchainLinkState.Interest -> view?.launchInterestDashboard()
            BlockchainLinkState.Receive -> view?.launchReceive()
            BlockchainLinkState.Send -> view?.launchSend()
            is BlockchainLinkState.Sell -> view?.launchBuySell(BuySellFragment.BuySellViewType.TYPE_SELL, link.ticker)
            is BlockchainLinkState.Activities -> view?.launchAssetAction(AssetAction.ViewActivity)
            is BlockchainLinkState.Buy -> view?.launchBuySell(BuySellFragment.BuySellViewType.TYPE_BUY, link.ticker)
            is BlockchainLinkState.SimpleBuy -> view?.launchSimpleBuy(link.ticker)
            is BlockchainLinkState.KycCampaign ->
                view?.launchKyc(valueOf<CampaignType>(link.campaignType.capitalizeFirstChar()) ?: CampaignType.None)
        }
    }
}
