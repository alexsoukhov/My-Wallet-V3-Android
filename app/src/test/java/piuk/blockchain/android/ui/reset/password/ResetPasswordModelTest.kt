package piuk.blockchain.android.ui.reset.password

import com.blockchain.android.testutils.rxInit
import com.blockchain.nabu.models.responses.nabu.NabuApiException
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.schedulers.Schedulers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import piuk.blockchain.androidcore.data.api.EnvironmentConfig
import retrofit2.HttpException
import retrofit2.Response

class ResetPasswordModelTest {

    private lateinit var model: ResetPasswordModel

    private val environmentConfig: EnvironmentConfig = mock {
        on { isRunningInDebugMode() }.thenReturn(false)
    }

    private val interactor: ResetPasswordInteractor = mock()

    @get:Rule
    val rx = rxInit {
        ioTrampoline()
        computationTrampoline()
    }

    @Before
    fun setUp() {
        model = ResetPasswordModel(
            initialState = ResetPasswordState(),
            mainScheduler = Schedulers.io(),
            environmentConfig = environmentConfig,
            crashLogger = mock(),
            interactor = interactor
        )
    }

    @Test
    fun `recover account and reset kyc successfully`() {
        val email = "email"
        val password = "password"
        val recoveryToken = "recovery_token"
        val walletName = "wallet_name"

        whenever(interactor.createWalletForAccount(email, password, walletName)).thenReturn(Completable.complete())
        whenever(interactor.recoverAccount(recoveryToken)).thenReturn(Completable.complete())
        whenever(interactor.resetUserKyc()).thenReturn(Completable.complete())

        val testState = model.state.test()
        model.process(
            ResetPasswordIntents.CreateWalletForAccount(
                email,
                password,
                recoveryToken,
                walletName,
                true
            )
        )

        testState.assertValues(
            ResetPasswordState(),
            ResetPasswordState(
                email = email,
                password = password,
                recoveryToken = recoveryToken,
                walletName = walletName,
                status = ResetPasswordStatus.CREATE_WALLET
            ),
            ResetPasswordState(
                email = email,
                password = password,
                recoveryToken = recoveryToken,
                walletName = walletName,
                status = ResetPasswordStatus.RECOVER_ACCOUNT
            ),
            ResetPasswordState(
                email = email,
                password = password,
                recoveryToken = recoveryToken,
                walletName = walletName,
                status = ResetPasswordStatus.RESET_KYC
            ),
            ResetPasswordState(
                email = email,
                password = password,
                recoveryToken = recoveryToken,
                walletName = walletName,
                status = ResetPasswordStatus.SHOW_SUCCESS
            )
        )
    }

    @Test
    fun `set password successfully`() {
        val password = "password"

        whenever(interactor.setNewPassword(password)).thenReturn(
            Completable.complete()
        )

        val testState = model.state.test()
        model.process(
            ResetPasswordIntents.SetNewPassword(
                password,
                false
            )
        )

        testState.assertValues(
            ResetPasswordState(),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.SET_PASSWORD
            ),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.SHOW_SUCCESS
            )
        )
    }

    @Test
    fun `fail to set new password should show error`() {
        val password = "password"

        whenever(interactor.setNewPassword(password)).thenReturn(
            Completable.error(
                Exception()
            )
        )

        val testState = model.state.test()
        model.process(
            ResetPasswordIntents.SetNewPassword(
                password,
                false
            )
        )

        testState.assertValues(
            ResetPasswordState(),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.SET_PASSWORD
            ),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.SHOW_ERROR
            )
        )
    }

    @Test
    fun `fail to reset kyc when resetting password should show error`() {
        val password = "password"

        whenever(interactor.setNewPassword(password)).thenReturn(
            Completable.complete()
        )
        whenever(interactor.resetUserKyc()).thenReturn(Completable.error(Exception()))

        val testState = model.state.test()
        model.process(
            ResetPasswordIntents.SetNewPassword(
                password,
                true
            )
        )

        testState.assertValues(
            ResetPasswordState(),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.SET_PASSWORD
            ),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.RESET_KYC
            ),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.SHOW_ERROR
            )
        )
    }

    @Test
    fun `reset kyc is already in progress when resetting password, continue`() {
        val password = "password"

        whenever(interactor.setNewPassword(password)).thenReturn(
            Completable.complete()
        )
        whenever(interactor.resetUserKyc()).thenReturn(
            Completable.error(
                NabuApiException.fromResponseBody(
                    HttpException(
                        Response.error<Unit>(
                            409,
                            KYC_IN_PROGRESS_ERROR_RESPONSE.toResponseBody(JSON_HEADER.toMediaTypeOrNull())
                        )
                    )
                )
            )
        )

        val testState = model.state.test()
        model.process(
            ResetPasswordIntents.SetNewPassword(
                password,
                true
            )
        )

        testState.assertValues(
            ResetPasswordState(),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.SET_PASSWORD
            ),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.RESET_KYC
            ),
            ResetPasswordState(
                password = password,
                status = ResetPasswordStatus.SHOW_SUCCESS
            )
        )
    }

    companion object {
        private const val JSON_HEADER = "application/json"
        private const val KYC_IN_PROGRESS_ERROR_RESPONSE =
            "{\"type\":\"CONFLICT\",\"description\":\"User reset in progress\"}"
    }
}