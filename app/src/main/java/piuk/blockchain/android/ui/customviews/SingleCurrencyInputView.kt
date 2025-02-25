package piuk.blockchain.android.ui.customviews

import android.content.Context
import android.text.Editable
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.blockchain.preferences.CurrencyPrefs
import info.blockchain.balance.AssetInfo
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.FiatValue
import info.blockchain.balance.Money
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import piuk.blockchain.android.databinding.EnterFiatCryptoLayoutBinding
import piuk.blockchain.android.ui.customviews.inputview.DecimalDigitsInputFilter
import piuk.blockchain.android.util.AfterTextChangedWatcher
import piuk.blockchain.android.util.gone
import piuk.blockchain.android.util.visible
import java.text.DecimalFormatSymbols
import java.util.Currency
import java.util.Locale
import kotlin.properties.Delegates

class SingleCurrencyInputView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs),
    KoinComponent {

    private val binding: EnterFiatCryptoLayoutBinding =
        EnterFiatCryptoLayoutBinding.inflate(LayoutInflater.from(context), this, true)

    val onImeAction: Observable<PrefixedOrSuffixedEditText.ImeOptions> by lazy {
        binding.enterAmount.onImeAction
    }

    private val amountSubject: PublishSubject<Money> = PublishSubject.create()
    private val currencyPrefs: CurrencyPrefs by inject()

    val amount: Observable<Money>
        get() = amountSubject

    val isConfigured: Boolean
        get() = configuration != SingleInputViewConfiguration.Undefined

    init {
        with(binding) {
            exchangeAmount.gone()

            enterAmount.addTextChangedListener(object : AfterTextChangedWatcher() {
                override fun afterTextChanged(s: Editable?) {
                    configuration.let {
                        when (it) {
                            is SingleInputViewConfiguration.Fiat -> {
                                val fiatAmount = enterAmount.bigDecimalValue?.let { amount ->
                                    FiatValue.fromMajor(it.fiatCurrency, amount)
                                } ?: FiatValue.zero(it.fiatCurrency)
                                amountSubject.onNext(fiatAmount)
                            }
                            is SingleInputViewConfiguration.Crypto -> {
                                val cryptoAmount = enterAmount.bigDecimalValue?.let { amount ->
                                    CryptoValue.fromMajor(it.cryptoCurrency, amount)
                                } ?: CryptoValue.zero(it.cryptoCurrency)

                                amountSubject.onNext(cryptoAmount)
                            }
                            is SingleInputViewConfiguration.Undefined -> {
                            }
                        }
                    }
                }
            })
        }
    }

    var maxLimit by Delegates.observable<Money>(
        FiatValue.fromMinor(
            currencyPrefs.defaultFiatCurrency,
            Long.MAX_VALUE
        )
    ) { _, oldValue, newValue ->
        if (newValue != oldValue)
            updateFilters(binding.enterAmount.configuration.prefixOrSuffix)
    }

    private fun updateFilters(prefixOrSuffix: String) {
        val maxDecimalDigitsForAmount = maxLimit.userDecimalPlaces
        val maxIntegerDigitsForAmount = maxLimit.toStringParts().major.length
        binding.enterAmount.addFilter(maxDecimalDigitsForAmount, maxIntegerDigitsForAmount, prefixOrSuffix)
    }

    var configuration by Delegates.observable<SingleInputViewConfiguration>(
        SingleInputViewConfiguration.Undefined
    ) { _, _, newValue ->
        with(binding.enterAmount) {
            filters = emptyArray()

            when (newValue) {
                is SingleInputViewConfiguration.Fiat -> {
                    val fiatSymbol = Currency.getInstance(newValue.fiatCurrency).getSymbol(Locale.getDefault())
                    updateFilters(fiatSymbol)
                    configuration = Configuration(
                        prefixOrSuffix = fiatSymbol,
                        isPrefix = true,
                        initialText = newValue.predefinedAmount.toStringWithoutSymbol()
                            .replace(DecimalFormatSymbols(Locale.getDefault()).groupingSeparator.toString(), "")
                            .removeSuffix("${DecimalFormatSymbols(Locale.getDefault()).decimalSeparator}00")
                    )
                    amountSubject.onNext(
                        newValue.predefinedAmount
                    )
                }
                is SingleInputViewConfiguration.Crypto -> {
                    val cryptoSymbol = newValue.cryptoCurrency.ticker
                    updateFilters(cryptoSymbol)
                    configuration = Configuration(
                        prefixOrSuffix = cryptoSymbol,
                        isPrefix = false,
                        initialText = newValue.predefinedAmount.toStringWithoutSymbol()
                            .replace(DecimalFormatSymbols(Locale.getDefault()).groupingSeparator.toString(), "")
                    )
                    amountSubject.onNext(
                        newValue.predefinedAmount
                    )
                }
                SingleInputViewConfiguration.Undefined -> {
                }
            }
        }
    }

    private fun PrefixedOrSuffixedEditText.addFilter(
        maxDecimalDigitsForAmount: Int,
        maxIntegerDigitsForAmount: Int,
        prefixOrSuffix: String
    ) {
        filters =
            arrayOf(
                DecimalDigitsInputFilter(
                    digitsAfterZero = maxDecimalDigitsForAmount,
                    prefixOrSuffix = prefixOrSuffix
                )
            )
    }

    fun showError(errorMessage: String) {
        with(binding) {
            error.text = errorMessage
            error.visible()
            exchangeAmount.gone()
            currencySwap.let {
                it.isEnabled = false
                it.alpha = .6f
            }
        }
    }

    fun hideError() {
        with(binding) {
            error.gone()
            exchangeAmount.visible()
            currencySwap.let {
                it.isEnabled = true
                it.alpha = 1f
            }
        }
    }
}

sealed class SingleInputViewConfiguration {
    object Undefined : SingleInputViewConfiguration()
    data class Fiat(
        val fiatCurrency: String,
        val predefinedAmount: FiatValue = FiatValue.zero(fiatCurrency)
    ) : SingleInputViewConfiguration()

    data class Crypto(
        val cryptoCurrency: AssetInfo,
        val predefinedAmount: CryptoValue = CryptoValue.zero(cryptoCurrency)
    ) : SingleInputViewConfiguration()
}