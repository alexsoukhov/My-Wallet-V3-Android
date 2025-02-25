package piuk.blockchain.android.ui.login

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import okhttp3.ResponseBody
import piuk.blockchain.android.util.AppUtil
import piuk.blockchain.androidcore.data.auth.AuthService
import piuk.blockchain.androidcore.data.payload.PayloadDataManager
import piuk.blockchain.androidcore.utils.PersistentPrefs

class LoginInteractor(
    private val authService: AuthService,
    private val payloadDataManager: PayloadDataManager,
    private val prefs: PersistentPrefs,
    private val appUtil: AppUtil
) {

    fun loginWithQrCode(qrString: String): Completable {

        return payloadDataManager.handleQrCode(qrString)
            .doOnComplete {
                payloadDataManager.wallet?.let { wallet ->
                    prefs.apply {
                        sharedKey = wallet.sharedKey
                        walletGuid = wallet.guid
                        emailVerified = true
                        isOnBoardingComplete = true
                    }
                }
            }
            .doOnError { appUtil.clearCredentials() }
    }

    fun obtainSessionId(email: String): Single<ResponseBody> {
        return authService.createSessionId(email)
    }

    fun sendEmailForVerification(sessionId: String, email: String, captcha: String): Completable {
        prefs.sessionId = sessionId
        return authService.sendEmailForDeviceVerification(sessionId, email, captcha)
            .ignoreElement()
    }
}