package piuk.blockchain.androidbuysell.services

import com.nhaarman.mockito_kotlin.whenever
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import org.amshove.kluent.`should be instance of`
import org.amshove.kluent.`should equal to`
import org.amshove.kluent.`should equal`
import org.amshove.kluent.mock
import org.junit.Before
import org.junit.Test
import piuk.blockchain.androidbuysell.MockWebServerTest
import piuk.blockchain.androidbuysell.api.PATH_COINFY_AUTH
import piuk.blockchain.androidbuysell.api.PATH_COINFY_GET_TRADER
import piuk.blockchain.androidbuysell.api.PATH_COINFY_KYC
import piuk.blockchain.androidbuysell.api.PATH_COINFY_PREP_KYC
import piuk.blockchain.androidbuysell.api.PATH_COINFY_SIGNUP_TRADER
import piuk.blockchain.androidbuysell.api.PATH_COINFY_TRADES
import piuk.blockchain.androidbuysell.api.PATH_COINFY_TRADES_PAYMENT_METHODS
import piuk.blockchain.androidbuysell.api.PATH_COINFY_TRADES_QUOTE
import piuk.blockchain.androidbuysell.models.coinify.AuthRequest
import piuk.blockchain.androidbuysell.models.coinify.BankDetails
import piuk.blockchain.androidbuysell.models.coinify.CannotTradeReasonAdapter
import piuk.blockchain.androidbuysell.models.coinify.DetailsAdapter
import piuk.blockchain.androidbuysell.models.coinify.ForcedDelay
import piuk.blockchain.androidbuysell.models.coinify.GrantType
import piuk.blockchain.androidbuysell.models.coinify.GrantTypeAdapter
import piuk.blockchain.androidbuysell.models.coinify.Medium
import piuk.blockchain.androidbuysell.models.coinify.MediumAdapter
import piuk.blockchain.androidbuysell.models.coinify.QuoteRequest
import piuk.blockchain.androidbuysell.models.coinify.ReviewState
import piuk.blockchain.androidbuysell.models.coinify.ReviewStateAdapter
import piuk.blockchain.androidbuysell.models.coinify.SignUpDetails
import piuk.blockchain.androidbuysell.models.coinify.TradeStateAdapter
import piuk.blockchain.androidbuysell.models.coinify.TransferStateAdapter
import piuk.blockchain.androidbuysell.models.coinify.exceptions.CoinifyApiException
import piuk.blockchain.androidbuysell.models.coinify.exceptions.CoinifyErrorCodes
import piuk.blockchain.androidcore.data.api.EnvironmentConfig
import piuk.blockchain.androidcore.data.rxjava.RxBus
import retrofit2.Retrofit
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory
import retrofit2.converter.moshi.MoshiConverterFactory

class CoinifyServiceTest : MockWebServerTest() {

    private lateinit var subject: CoinifyService
    private val rxBus = RxBus()
    private val moshi: Moshi = Moshi.Builder()
            .add(CannotTradeReasonAdapter())
            .add(ReviewStateAdapter())
            .add(MediumAdapter())
            .add(TradeStateAdapter())
            .add(TransferStateAdapter())
            .add(DetailsAdapter())
            .add(GrantTypeAdapter())
            .build()
    private val moshiConverterFactory = MoshiConverterFactory.create(moshi)
    private val rxJava2CallAdapterFactory = RxJava2CallAdapterFactory.create()
    private val environmentConfig: EnvironmentConfig = mock()

    @Before
    override fun setUp() {
        super.setUp()

        val okHttpClient = OkHttpClient.Builder()
                .build()
        val retrofit = Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl(server.url("/").toString())
                .addConverterFactory(moshiConverterFactory)
                .addCallAdapterFactory(rxJava2CallAdapterFactory)
                .build()

        whenever(environmentConfig.coinifyUrl).thenReturn("")

        subject = CoinifyService(environmentConfig, retrofit, rxBus)
    }

    @Test
    fun `sign up success`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(TRADER_RESPONSE)
        )
        // Act
        val testObserver = subject.signUp(
                path = PATH_COINFY_SIGNUP_TRADER,
                signUpDetails = SignUpDetails.basicSignUp(
                        "example@email.com",
                        "USD",
                        "US",
                        "token"
                )
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        val traderResponse = testObserver.values().first()
        traderResponse.trader.id `should equal to` 754035
        traderResponse.trader.profile.address.countryCode `should equal to` "US"
        server.takeRequest().path `should equal to` "/$PATH_COINFY_SIGNUP_TRADER"
    }

    @Test
    fun `sign up failure test error code parsing`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(400)
                        .setBody(SIGNUP_ERROR_RESPONSE)
        )
        // Act
        val testObserver = subject.signUp(
                path = PATH_COINFY_SIGNUP_TRADER,
                signUpDetails = SignUpDetails.basicSignUp(
                        "example@email.com",
                        "USD",
                        "US",
                        "token"
                )
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertFailure(CoinifyApiException::class.java)
        val throwable = testObserver.errors()[0]
        throwable as CoinifyApiException
        throwable.getErrorCode() `should equal` CoinifyErrorCodes.EmailAddressInUse
        server.takeRequest().path `should equal to` "/$PATH_COINFY_SIGNUP_TRADER"
    }

    @Test
    fun `getTrader success`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(TRADER_RESPONSE)
        )
        val accessToken = "ACCESS_TOKEN"
        // Act
        val testObserver = subject.getTrader(
                path = PATH_COINFY_GET_TRADER,
                accessToken = accessToken
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        val traderResponse = testObserver.values().first()
        traderResponse.trader.id `should equal to` 754035
        traderResponse.trader.profile.address.countryCode `should equal to` "US"
        val request = server.takeRequest()
        request.path `should equal to` "/$PATH_COINFY_GET_TRADER"
        request.headers.get("Authorization") `should equal` "Bearer $accessToken"
    }

    @Test
    fun `getTrades success`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(TRADE_LIST_RESPONSE)
        )
        val accessToken = "ACCESS_TOKEN"
        // Act
        val testObserver = subject.getTrades(
                path = PATH_COINFY_TRADES,
                accessToken = accessToken
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        val list = testObserver.values().first()
        list[0].transferIn.medium `should equal` Medium.Bank
        list[0].transferIn.details `should be instance of` BankDetails::class
        val request = server.takeRequest()
        request.path `should equal to` "/$PATH_COINFY_TRADES"
        request.headers.get("Authorization") `should equal` "Bearer $accessToken"
    }

    @Test
    fun `getTradeStatus success`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(GET_TRADE_STATUS_RESPONSE)
        )
        val accessToken = "ACCESS_TOKEN"
        // Act
        val testObserver = subject.getTradeStatus(
                path = PATH_COINFY_TRADES,
                accessToken = accessToken,
                tradeId = 12345
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        val trade = testObserver.values().first()
        trade.transferIn.details `should be instance of` BankDetails::class
        val request = server.takeRequest()
        request.path `should equal to` "/$PATH_COINFY_TRADES/12345"
        request.headers.get("Authorization") `should equal` "Bearer $accessToken"
    }

    @Test
    fun `auth success`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(AUTH_RESPONSE)
        )
        // Act
        val testObserver = subject.auth(
                path = PATH_COINFY_AUTH,
                authRequest = AuthRequest(
                        grantType = GrantType.OfflineToken,
                        offlineToken = "OFFLINE_TOKEN"
                )
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        val authResponse = testObserver.values().first()
        authResponse.tokenType `should equal` "bearer"
        authResponse.expiresIn `should equal` 1200
        val request = server.takeRequest()
        request.path `should equal to` "/$PATH_COINFY_AUTH"
        // Check Moshi's handling of enum class w/overridden toString method
        val inputAsString = request.body.inputStream().bufferedReader().use { it.readText() }
        val adapter = moshi.adapter(AuthRequest::class.java)
        val (grantType, offlineToken) = adapter.fromJson(inputAsString)!!
        grantType `should equal` GrantType.OfflineToken
        offlineToken `should equal to` "OFFLINE_TOKEN"
    }

    @Test
    fun `startKycReview success`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(KYC_RESPONSE)
        )
        val accessToken = "ACCESS_TOKEN"
        // Act
        val testObserver = subject.startKycReview(
                path = PATH_COINFY_PREP_KYC,
                accessToken = accessToken
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        val kycResponse = testObserver.values().first()
        kycResponse.state `should equal` ReviewState.Completed
        val request = server.takeRequest()
        request.path `should equal to` "/$PATH_COINFY_PREP_KYC"
        request.headers.get("Authorization") `should equal` "Bearer $accessToken"
    }

    @Test
    fun `getKycReviewStatus success`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(KYC_RESPONSE)
        )
        val accessToken = "ACCESS_TOKEN"
        // Act
        val testObserver = subject.getKycReviewStatus(
                path = PATH_COINFY_KYC,
                id = 12345,
                accessToken = accessToken
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        val kycResponse = testObserver.values().first()
        kycResponse.state `should equal` ReviewState.Completed
        val request = server.takeRequest()
        request.path `should equal to` "/$PATH_COINFY_KYC/12345"
        request.headers.get("Authorization") `should equal` "Bearer $accessToken"
    }

    @Test
    fun `getQuote with amount parameter success`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(AUTHENTICATED_QUOTE_RESPONSE)
        )
        val accessToken = "ACCESS_TOKEN"
        // Act
        val testObserver = subject.getQuote(
                path = PATH_COINFY_TRADES_QUOTE,
                quoteRequest = QuoteRequest("BTC", "USD", 0.001),
                accessToken = accessToken
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        val quote = testObserver.values().first()
        quote.baseCurrency `should equal to` "USD"
        quote.quoteCurrency `should equal to` "BTC"
        quote.id `should equal` 123456
        quote.baseAmount `should equal to` -1000.00
        quote.quoteAmount `should equal to` 2.41551728
        val request = server.takeRequest()
        request.path `should equal to` "/$PATH_COINFY_TRADES_QUOTE"
        request.headers.get("Authorization") `should equal` "Bearer $accessToken"
        // Check outgoing JSON
        val inputAsString = request.body.inputStream().bufferedReader().use { it.readText() }
        val adapter = moshi.adapter(QuoteRequest::class.java)
        val (baseCurrency, quoteCurrency, baseAmount) = adapter.fromJson(inputAsString)!!
        baseCurrency `should equal to` "BTC"
        quoteCurrency `should equal to` "USD"
        baseAmount `should equal to` 0.001
    }

    @Test
    fun `get payment methods success`() {
        // Arrange
        server.enqueue(
                MockResponse()
                        .setResponseCode(200)
                        .setBody(PAYMENT_METHODS_RESPONSE)
        )
        val accessToken = "ACCESS_TOKEN"
        // Act
        val testObserver = subject.getPaymentMethods(
                path = PATH_COINFY_TRADES_PAYMENT_METHODS,
                inCurrency = "USD",
                outCurrency = "BTC",
                accessToken = accessToken
        ).test()
        // Assert
        testObserver.awaitTerminalEvent()
        testObserver.assertComplete()
        testObserver.assertNoErrors()
        val methods = testObserver.values().first()
        val bankInMethod = methods.first()
        bankInMethod.inMedium `should equal to` "bank"
        val blockchainInMethod = methods[1]
        blockchainInMethod.inMedium `should equal to` "blockchain"
        val cardInMethod = methods[2]
        cardInMethod.inMedium `should equal to` "card"
        cardInMethod.canTrade `should equal to` false
        cardInMethod.cannotTradeReasons!!.first() `should be instance of` ForcedDelay::class
        val request = server.takeRequest()
        request.path `should equal to` "/$PATH_COINFY_TRADES_PAYMENT_METHODS?inCurrency=USD&outCurrency=BTC"
        request.headers.get("Authorization") `should equal` "Bearer $accessToken"
    }

    companion object {

        private const val TRADER_RESPONSE = "{\n" +
                "  \"trader\": {\n" +
                "    \"id\": 754035,\n" +
                "    \"email\": \"example@email.com\",\n" +
                "    \"defaultCurrency\": \"USD\",\n" +
                "    \"profile\": {\n" +
                "      \"address\": {\n" +
                "        \"country\": \"US\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"level\": {}\n" +
                "  },\n" +
                "  \"offlineToken\": \"aGFja2VydHlwZXIuY29tIGlzIG15IElERQ==\"\n" +
                "}"

        private const val AUTHENTICATED_QUOTE_RESPONSE = "{\n" +
                "  \"id\": 123456,\n" +
                "  \"baseCurrency\": \"USD\",\n" +
                "  \"quoteCurrency\": \"BTC\",\n" +
                "  \"baseAmount\": -1000.00,\n" +
                "  \"quoteAmount\": 2.41551728,\n" +
                "  \"issueTime\": \"2016-04-01T11:47:24Z\",\n" +
                "  \"expiryTime\": \"2016-04-01T12:02:24Z\"\n" +
                "}"

        private const val PAYMENT_METHODS_RESPONSE = "" +
                "[\n" +
                "    {\n" +
                "      \"inMedium\": \"bank\",           \n" +
                "      \"outMedium\": \"blockchain\",    \n" +
                "      \"name\": \"Buy bitcoins with bank transfer\",    \n" +
                "      \"inCurrencies\": [\"DKK\", \"EUR\", \"GBP\", \"USD\"],\n" +
                "      \"outCurrencies\": [\"BTC\"],\n" +
                "      \"inFixedFees\": {\n" +
                "        \"DKK\": 0,\n" +
                "        \"EUR\": 0,\n" +
                "        \"GBP\": 0,\n" +
                "        \"USD\": 0\n" +
                "      },\n" +
                "      \"inPercentageFee\": 0,\n" +
                "      \"outFixedFees\": {\n" +
                "        \"BTC\": 0.001\n" +
                "      },\n" +
                "      \"outPercentageFee\": 0,\n" +
                "      \"minimumInAmounts\": {\n" +
                "        \"DKK\": 75.86,\n" +
                "        \"EUR\": 10.00,\n" +
                "        \"GBP\": 8.00,\n" +
                "        \"USD\": 12.50\n" +
                "      },\n" +
                "      \"limitInAmounts\": {\n" +
                "        \"DKK\": 7500.86,\n" +
                "        \"EUR\": 1000.00,\n" +
                "        \"GBP\": 8000.00,\n" +
                "        \"USD\": 1200.50\n" +
                "      },\n" +
                "      \"limitOutAmounts\": {\n" +
                "        \"BTC\": 0.8\n" +
                "      },\n" +
                "      \"canTrade\": true\n" +
                "    },\n" +
                "    {\n" +
                "      \"inMedium\": \"blockchain\",           \n" +
                "      \"outMedium\": \"bank\",    \n" +
                "      \"name\": \"Sell bitcoints to bank transfer\",    \n" +
                "      \"inCurrencies\": [\"BTC\"],\n" +
                "      \"outCurrencies\": [\"DKK\", \"EUR\", \"GBP\", \"USD\"],\n" +
                "      \"inFixedFees\": {\n" +
                "        \"BTC\": 0\n" +
                "      },\n" +
                "      \"inPercentageFee\": 0,\n" +
                "      \"outFixedFees\": {\n" +
                "        \"DKK\": 40.00,\n" +
                "        \"EUR\": 5.40,\n" +
                "        \"GBP\": 3.70,\n" +
                "        \"USD\": 6.10\n" +
                "      },\n" +
                "      \"outPercentageFee\": 0,\n" +
                "      \"minimumInAmounts\": {\n" +
                "        \"BTC\": 0.0102\n" +
                "      },\n" +
                "      \"limitInAmounts\": {\n" +
                "        \"BTC\": 1.86\n" +
                "      },\n" +
                "      \"limitOutAmounts\": {\n" +
                "        \"DKK\": 7500.86,\n" +
                "        \"EUR\": 1000.00,\n" +
                "        \"GBP\": 8000.00,\n" +
                "        \"USD\": 1200.50\n" +
                "      },\n" +
                "      \"canTrade\": true\n" +
                "    },\n" +
                "    {\n" +
                "      \"inMedium\": \"card\",           \n" +
                "      \"outMedium\": \"blockchain\",    \n" +
                "      \"name\": \"Buy bitcoins with card transfer\",    \n" +
                "      \"inCurrencies\": [\"DKK\", \"EUR\", \"GBP\", \"USD\"],\n" +
                "      \"outCurrencies\": [\"BTC\"],\n" +
                "      \"inFixedFees\": {\n" +
                "        \"DKK\": 0,\n" +
                "        \"EUR\": 0,\n" +
                "        \"GBP\": 0,\n" +
                "        \"USD\": 0\n" +
                "      },\n" +
                "      \"inPercentageFee\": 3,\n" +
                "      \"outFixedFees\": {\n" +
                "        \"BTC\": 0.001\n" +
                "      },\n" +
                "      \"outPercentageFee\": 0,\n" +
                "      \"minimumInAmounts\": {\n" +
                "        \"DKK\": 75.86,\n" +
                "        \"EUR\": 10.00,\n" +
                "        \"GBP\": 8.00,\n" +
                "        \"USD\": 12.50\n" +
                "      },\n" +
                "      \"limitInAmounts\": {\n" +
                "        \"DKK\": 75.86,\n" +
                "        \"EUR\": 10.00,\n" +
                "        \"GBP\": 8.00,\n" +
                "        \"USD\": 12.50\n" +
                "      },\n" +
                "      \"limitOutAmounts\": {\n" +
                "        \"BTC\": 0.8\n" +
                "      },\n" +
                "      \"canTrade\": false,\n" +
                "      \"cannotTradeReasons\": [\n" +
                "        {\n" +
                "          \"reasonCode\": \"forced_delay\",\n" +
                "          \"delayEnd\": \"2016-04-01T12:27:36Z\"\n" +
                "        }\n" +
                "      ]\n" +
                "    }\n" +
                "]"

        private const val AUTH_RESPONSE = "{\n" +
                "  \"access_token\": \"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiYWRtaW4iOnRydWV9.TJVA95OrM7E2cBab30RMHrHDcEfxjoYZgeFONFh7HgQ\",\n" +
                "  \"token_type\": \"bearer\",\n" +
                "  \"expires_in\": 1200,\n" +
                "  \"refresh_token\": \"wt5RoH8i6HkSQvI8kFpEBLEIB6lw8lOpYKHEz0ND9znDaAOtH1dFI32GqhvT9PGC\"\n" +
                "}"

        private const val KYC_RESPONSE = "{\n" +
                "  \"id\": 55555,\n" +
                "  \"state\": \"completed\",\n" +
                "  \"returnUrl\": \"https://mypage.com/kyc_complete\",\n" +
                "  \"redirectUrl\": \"https://example.com/url/to/perform/kyc/review\",\n" +
                "  \"externalId\": \"1234-abcd-5678-f33d\",\n" +
                "  \"updateTime\": \"2016-07-07T12:11:36Z\",\n" +
                "  \"createTime\": \"2016-07-07T12:10:19Z\"\n" +
                "}"

        private const val TRADE_LIST_RESPONSE = "[\n" +
                "  {\n" +
                "    \"id\": 1866501,\n" +
                "    \"traderId\": 1122,\n" +
                "    \"state\": \"awaiting_transfer_in\",\n" +
                "    \"inCurrency\": \"GBP\",\n" +
                "    \"outCurrency\": \"BTC\",\n" +
                "    \"inAmount\": 100,\n" +
                "    \"outAmountExpected\": 0.01684238,\n" +
                "    \"transferIn\": {\n" +
                "      \"id\": 3688710,\n" +
                "      \"currency\": \"GBP\",\n" +
                "      \"state\": \"waiting\",\n" +
                "      \"sendAmount\": 100.25,\n" +
                "      \"receiveAmount\": 100,\n" +
                "      \"medium\": \"bank\",\n" +
                "      \"details\": {\n" +
                "        \"bank\": {\n" +
                "          \"name\": \"Example Bank\",\n" +
                "          \"address\": {\n" +
                "            \"city\": \"London\",\n" +
                "            \"street\": \"25 London Road\",\n" +
                "            \"country\": \"GB\",\n" +
                "            \"zipcode\": \"W1 1W\"\n" +
                "          }\n" +
                "        },\n" +
                "        \"holder\": {\n" +
                "          \"name\": \"Coinify\",\n" +
                "          \"address\": {\n" +
                "            \"city\": \"Herlev\",\n" +
                "            \"street\": \"Herlev\",\n" +
                "            \"country\": \"DK\",\n" +
                "            \"zipcode\": \"12345\"\n" +
                "          }\n" +
                "        },\n" +
                "        \"account\": {\n" +
                "          \"bic\": \"BIC\",\n" +
                "          \"type\": \"international\",\n" +
                "          \"number\": \"GB1234567890\",\n" +
                "          \"currency\": \"GBP\"\n" +
                "        },\n" +
                "        \"referenceText\": \"AB1234567\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"transferOut\": {\n" +
                "      \"id\": 3688711,\n" +
                "      \"currency\": \"BTC\",\n" +
                "      \"state\": \"waiting\",\n" +
                "      \"sendAmount\": 0.01684238,\n" +
                "      \"receiveAmount\": 0.01682958,\n" +
                "      \"medium\": \"blockchain\",\n" +
                "      \"details\": {\n" +
                "        \"account\": \"138Tmtxqqc9VGVBtSH6gRs8VG9G1zUZgbp\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"quoteExpireTime\": \"2018-04-19T09:41:59.000Z\",\n" +
                "    \"updateTime\": \"2018-04-19T09:27:28.721Z\",\n" +
                "    \"createTime\": \"2018-04-19T09:27:28.721Z\",\n" +
                "    \"tradeSubscriptionId\": null\n" +
                "  },\n" +
                "  {\n" +
                "    \"id\": 376183,\n" +
                "    \"traderId\": 1122,\n" +
                "    \"state\": \"rejected\",\n" +
                "    \"inCurrency\": \"USD\",\n" +
                "    \"outCurrency\": \"BTC\",\n" +
                "    \"inAmount\": 15,\n" +
                "    \"outAmountExpected\": 0.00567297,\n" +
                "    \"transferIn\": {\n" +
                "      \"id\": 772587,\n" +
                "      \"currency\": \"USD\",\n" +
                "      \"state\": \"rejected\",\n" +
                "      \"sendAmount\": 15.45,\n" +
                "      \"receiveAmount\": 15,\n" +
                "      \"medium\": \"card\",\n" +
                "      \"details\": {\n" +
                "        \"provider\": \"isignthis\",\n" +
                "        \"paymentId\": \"a0895735-1234-1234-1234-47ecfb05ea7b\",\n" +
                "        \"redirectUrl\": \"https://coinify-verify.isignthis.com/landing/a0895735-1234-1234-1234-47ecfb05ea7b\",\n" +
                "        \"cardPaymentId\": 352518\n" +
                "      }\n" +
                "    },\n" +
                "    \"transferOut\": {\n" +
                "      \"id\": 772588,\n" +
                "      \"currency\": \"BTC\",\n" +
                "      \"state\": \"cancelled\",\n" +
                "      \"sendAmount\": 0.00567297,\n" +
                "      \"receiveAmount\": 0.00567297,\n" +
                "      \"medium\": \"blockchain\",\n" +
                "      \"details\": {\n" +
                "        \"account\": \"16yaQgjFfViVyekj6XKNyTzX7Mu4bqmBMQ\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"updateTime\": \"2017-07-06T13:25:31.810Z\",\n" +
                "    \"createTime\": \"2017-07-06T13:24:05.039Z\",\n" +
                "    \"tradeSubscriptionId\": null\n" +
                "  },\n" +
                "  {\n" +
                "    \"id\": 171999,\n" +
                "    \"traderId\": 1122,\n" +
                "    \"state\": \"completed\",\n" +
                "    \"inCurrency\": \"GBP\",\n" +
                "    \"outCurrency\": \"BTC\",\n" +
                "    \"inAmount\": 20,\n" +
                "    \"outAmountExpected\": 0.02058381,\n" +
                "    \"outAmount\": 0.02058381,\n" +
                "    \"transferIn\": {\n" +
                "      \"id\": 357223,\n" +
                "      \"currency\": \"GBP\",\n" +
                "      \"state\": \"completed\",\n" +
                "      \"sendAmount\": 20.6,\n" +
                "      \"receiveAmount\": 20,\n" +
                "      \"medium\": \"card\",\n" +
                "      \"details\": {\n" +
                "        \"provider\": \"isignthis\",\n" +
                "        \"paymentId\": \"6b1b3b78-1234-1234-1234-6b73b85d69db\",\n" +
                "        \"redirectUrl\": \"https://coinify-verify.isignthis.com/landing/6b1b3b78-1234-1234-1234-6b73b85d69db\",\n" +
                "        \"cardPaymentId\": 170105\n" +
                "      }\n" +
                "    },\n" +
                "    \"transferOut\": {\n" +
                "      \"id\": 357224,\n" +
                "      \"currency\": \"BTC\",\n" +
                "      \"state\": \"completed\",\n" +
                "      \"sendAmount\": 0.02058381,\n" +
                "      \"receiveAmount\": 0.02058381,\n" +
                "      \"medium\": \"blockchain\",\n" +
                "      \"details\": {\n" +
                "        \"account\": \"1H9iXThZXAsyfPxaMyVLjYTkyidy6sSDNR\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"receiptUrl\": \"https://merchant.coinify.com/receipts/171999\",\n" +
                "    \"updateTime\": \"2017-04-13T10:03:49.873Z\",\n" +
                "    \"createTime\": \"2017-04-13T09:46:11.846Z\",\n" +
                "    \"tradeSubscriptionId\": null\n" +
                "  },\n" +
                "  {\n" +
                "    \"id\": 6371,\n" +
                "    \"traderId\": 1122,\n" +
                "    \"state\": \"completed\",\n" +
                "    \"inCurrency\": \"GBP\",\n" +
                "    \"outCurrency\": \"BTC\",\n" +
                "    \"inAmount\": 10,\n" +
                "    \"outAmountExpected\": 0.01671137,\n" +
                "    \"outAmount\": 0.01671137,\n" +
                "    \"transferIn\": {\n" +
                "      \"id\": 14288,\n" +
                "      \"currency\": \"GBP\",\n" +
                "      \"state\": \"completed\",\n" +
                "      \"sendAmount\": 10.3,\n" +
                "      \"receiveAmount\": 10,\n" +
                "      \"medium\": \"card\",\n" +
                "      \"details\": {\n" +
                "        \"paymentId\": \"4820c88e-1234-1234-1234-5d95ba002757\",\n" +
                "        \"redirectUrl\": \"https://coinify-verify.isignthis.com/landing/4820c88e-1234-1234-1234-5d95ba002757\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"transferOut\": {\n" +
                "      \"id\": 14289,\n" +
                "      \"currency\": \"BTC\",\n" +
                "      \"state\": \"completed\",\n" +
                "      \"sendAmount\": 0.01671137,\n" +
                "      \"receiveAmount\": 0.01671137,\n" +
                "      \"medium\": \"blockchain\",\n" +
                "      \"details\": {\n" +
                "        \"account\": \"1vJ4GgmRYnSRxd9SqKZmv2ibAJ7qjar9r\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"receiptUrl\": \"https://merchant.coinify.com/receipts/6371\",\n" +
                "    \"updateTime\": \"2016-11-09T19:07:36.566Z\",\n" +
                "    \"createTime\": \"2016-11-09T19:06:19.284Z\",\n" +
                "    \"tradeSubscriptionId\": null\n" +
                "  },\n" +
                "  {\n" +
                "    \"id\": 2570,\n" +
                "    \"traderId\": 1122,\n" +
                "    \"state\": \"completed\",\n" +
                "    \"inCurrency\": \"GBP\",\n" +
                "    \"outCurrency\": \"BTC\",\n" +
                "    \"inAmount\": 20,\n" +
                "    \"outAmountExpected\": 0.04482082,\n" +
                "    \"outAmount\": 0.04482082,\n" +
                "    \"transferIn\": {\n" +
                "      \"id\": 6012,\n" +
                "      \"currency\": \"GBP\",\n" +
                "      \"state\": \"completed\",\n" +
                "      \"sendAmount\": 20.6,\n" +
                "      \"receiveAmount\": 20,\n" +
                "      \"medium\": \"card\",\n" +
                "      \"details\": {\n" +
                "        \"paymentId\": \"50787d4c-1234-1234-1234-45ec57b374b2\",\n" +
                "        \"redirectUrl\": \"https://coinify-verify.isignthis.com/landing/50787d4c-1234-1234-1234-45ec57b374b2\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"transferOut\": {\n" +
                "      \"id\": 6013,\n" +
                "      \"currency\": \"BTC\",\n" +
                "      \"state\": \"completed\",\n" +
                "      \"sendAmount\": 0.04482082,\n" +
                "      \"receiveAmount\": 0.04482082,\n" +
                "      \"medium\": \"blockchain\",\n" +
                "      \"details\": {\n" +
                "        \"account\": \"12mqJh5Y295Tw9dTs4EqfhqcsjxUuDzFiq\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"receiptUrl\": \"https://merchant.coinify.com/receipts/2570\",\n" +
                "    \"updateTime\": \"2016-09-01T06:42:42.432Z\",\n" +
                "    \"createTime\": \"2016-08-31T16:31:29.881Z\",\n" +
                "    \"tradeSubscriptionId\": null\n" +
                "  },\n" +
                "  {\n" +
                "    \"id\": 2550,\n" +
                "    \"traderId\": 1122,\n" +
                "    \"state\": \"cancelled\",\n" +
                "    \"inCurrency\": \"GBP\",\n" +
                "    \"outCurrency\": \"BTC\",\n" +
                "    \"inAmount\": 20,\n" +
                "    \"outAmountExpected\": 0.04485608,\n" +
                "    \"transferIn\": {\n" +
                "      \"id\": 5972,\n" +
                "      \"currency\": \"GBP\",\n" +
                "      \"state\": \"expired\",\n" +
                "      \"sendAmount\": 20.6,\n" +
                "      \"receiveAmount\": 20,\n" +
                "      \"medium\": \"card\",\n" +
                "      \"details\": {\n" +
                "        \"paymentId\": \"42ba4aec-1234-1234-1324-ca2b25a8b836\",\n" +
                "        \"redirectUrl\": \"https://coinify-verify.isignthis.com/landing/42ba4aec-1234-1234-1234-ca2b25a8b836\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"transferOut\": {\n" +
                "      \"id\": 5973,\n" +
                "      \"currency\": \"BTC\",\n" +
                "      \"state\": \"cancelled\",\n" +
                "      \"sendAmount\": 0.04485608,\n" +
                "      \"receiveAmount\": 0.04485608,\n" +
                "      \"medium\": \"blockchain\",\n" +
                "      \"details\": {\n" +
                "        \"account\": \"1HC1mUpXqdR4xd6KnYTxpLupvbPMo3dYgN\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"updateTime\": \"2016-08-31T16:31:19.569Z\",\n" +
                "    \"createTime\": \"2016-08-31T12:21:45.032Z\",\n" +
                "    \"tradeSubscriptionId\": null\n" +
                "  }\n" +
                "]"

        private const val GET_TRADE_STATUS_RESPONSE = "  {\n" +
                "    \"id\": 1866501,\n" +
                "    \"traderId\": 1122,\n" +
                "    \"state\": \"awaiting_transfer_in\",\n" +
                "    \"inCurrency\": \"GBP\",\n" +
                "    \"outCurrency\": \"BTC\",\n" +
                "    \"inAmount\": 100,\n" +
                "    \"outAmountExpected\": 0.01684238,\n" +
                "    \"transferIn\": {\n" +
                "      \"id\": 3688710,\n" +
                "      \"currency\": \"GBP\",\n" +
                "      \"state\": \"waiting\",\n" +
                "      \"sendAmount\": 100.25,\n" +
                "      \"receiveAmount\": 100,\n" +
                "      \"medium\": \"bank\",\n" +
                "      \"details\": {\n" +
                "        \"bank\": {\n" +
                "          \"name\": \"Example Bank\",\n" +
                "          \"address\": {\n" +
                "            \"city\": \"London\",\n" +
                "            \"street\": \"25 London Road\",\n" +
                "            \"country\": \"GB\",\n" +
                "            \"zipcode\": \"W1 1W\"\n" +
                "          }\n" +
                "        },\n" +
                "        \"holder\": {\n" +
                "          \"name\": \"Coinify\",\n" +
                "          \"address\": {\n" +
                "            \"city\": \"Herlev\",\n" +
                "            \"street\": \"Herlev\",\n" +
                "            \"country\": \"DK\",\n" +
                "            \"zipcode\": \"12345\"\n" +
                "          }\n" +
                "        },\n" +
                "        \"account\": {\n" +
                "          \"bic\": \"BIC\",\n" +
                "          \"type\": \"international\",\n" +
                "          \"number\": \"GB1234567890\",\n" +
                "          \"currency\": \"GBP\"\n" +
                "        },\n" +
                "        \"referenceText\": \"AB1234567\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"transferOut\": {\n" +
                "      \"id\": 3688711,\n" +
                "      \"currency\": \"BTC\",\n" +
                "      \"state\": \"waiting\",\n" +
                "      \"sendAmount\": 0.01684238,\n" +
                "      \"receiveAmount\": 0.01682958,\n" +
                "      \"medium\": \"blockchain\",\n" +
                "      \"details\": {\n" +
                "        \"account\": \"138Tmtxqqc9VGVBtSH6gRs8VG9G1zUZgbp\"\n" +
                "      }\n" +
                "    },\n" +
                "    \"quoteExpireTime\": \"2018-04-19T09:41:59.000Z\",\n" +
                "    \"updateTime\": \"2018-04-19T09:27:28.721Z\",\n" +
                "    \"createTime\": \"2018-04-19T09:27:28.721Z\",\n" +
                "    \"tradeSubscriptionId\": null\n" +
                "  }"

        private const val SIGNUP_ERROR_RESPONSE = "{\n" +
                "  \"error\": \"email_address_in_use\",\n" +
                "  \"error_description\": \"The provided email address is already associated with an existing trader.\"\n" +
                "}"

    }
}