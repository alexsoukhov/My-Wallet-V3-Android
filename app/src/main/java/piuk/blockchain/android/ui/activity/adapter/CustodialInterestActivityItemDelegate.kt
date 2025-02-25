package piuk.blockchain.android.ui.activity.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blockchain.nabu.datamanagers.InterestState
import info.blockchain.balance.AssetInfo
import com.blockchain.preferences.CurrencyPrefs
import com.blockchain.utils.toFormattedDate
import info.blockchain.wallet.multiaddress.TransactionSummary
import io.reactivex.rxjava3.disposables.CompositeDisposable
import piuk.blockchain.android.R
import piuk.blockchain.android.coincore.CustodialInterestActivitySummaryItem
import piuk.blockchain.android.databinding.DialogActivitiesTxItemBinding
import piuk.blockchain.android.ui.activity.CryptoActivityType
import piuk.blockchain.android.ui.adapters.AdapterDelegate
import piuk.blockchain.android.util.context
import piuk.blockchain.android.util.getResolvedColor
import piuk.blockchain.android.util.gone
import piuk.blockchain.android.util.setAssetIconColoursWithTint
import piuk.blockchain.android.util.setTransactionHasFailed
import java.util.Date

class CustodialInterestActivityItemDelegate<in T>(
    private val currencyPrefs: CurrencyPrefs,
    private val onItemClicked: (AssetInfo, String, CryptoActivityType) -> Unit // crypto, txID, type
) : AdapterDelegate<T> {

    override fun isForViewType(items: List<T>, position: Int): Boolean =
        items[position] is CustodialInterestActivitySummaryItem

    override fun onCreateViewHolder(parent: ViewGroup): RecyclerView.ViewHolder =
        CustodialInterestActivityItemViewHolder(
            DialogActivitiesTxItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(
        items: List<T>,
        position: Int,
        holder: RecyclerView.ViewHolder
    ) = (holder as CustodialInterestActivityItemViewHolder).bind(
        items[position] as CustodialInterestActivitySummaryItem,
        currencyPrefs.selectedFiatCurrency,
        onItemClicked
    )
}

private class CustodialInterestActivityItemViewHolder(
    private val binding: DialogActivitiesTxItemBinding
) : RecyclerView.ViewHolder(binding.root) {

    private val disposables: CompositeDisposable = CompositeDisposable()

    fun bind(
        tx: CustodialInterestActivitySummaryItem,
        selectedFiatCurrency: String,
        onAccountClicked: (AssetInfo, String, CryptoActivityType) -> Unit
    ) {
        disposables.clear()
        with(binding) {
            icon.setIcon(tx.isPending(), tx.type)
            when {
                tx.status.isPending().not() -> {
                    icon.setAssetIconColoursWithTint(tx.asset)
                }
                tx.status.hasFailed() -> icon.setTransactionHasFailed()
                else -> {
                    icon.background = null
                    icon.setColorFilter(Color.TRANSPARENT)
                }
            }

            assetBalanceFiat.gone()
            assetBalanceCrypto.text = tx.value.toStringWithSymbol()
            assetBalanceFiat.bindAndConvertFiatBalance(tx, disposables, selectedFiatCurrency)

            txType.setTxLabel(tx.asset, tx.type)
            statusDate.setTxStatus(tx)
            setTextColours(tx.status)

            txRoot.setOnClickListener {
                onAccountClicked(tx.asset, tx.txId, CryptoActivityType.CUSTODIAL_INTEREST)
            }
        }
    }

    private fun setTextColours(txStatus: InterestState) {
        with(binding) {
            if (txStatus.isCompleted()) {
                txType.setTextColor(context.getResolvedColor(R.color.black))
                statusDate.setTextColor(context.getResolvedColor(R.color.grey_600))
                assetBalanceFiat.setTextColor(context.getResolvedColor(R.color.grey_600))
                assetBalanceCrypto.setTextColor(context.getResolvedColor(R.color.black))
            } else {
                txType.setTextColor(context.getResolvedColor(R.color.grey_400))
                statusDate.setTextColor(context.getResolvedColor(R.color.grey_400))
                assetBalanceFiat.setTextColor(context.getResolvedColor(R.color.grey_400))
                assetBalanceCrypto.setTextColor(context.getResolvedColor(R.color.grey_400))
            }
        }
    }
}

private fun InterestState.isPending(): Boolean =
    this == InterestState.PENDING ||
        this == InterestState.PROCESSING ||
        this == InterestState.MANUAL_REVIEW

private fun InterestState.hasFailed(): Boolean =
    this == InterestState.FAILED

private fun InterestState.isCompleted(): Boolean =
    this == InterestState.COMPLETE

private fun ImageView.setIcon(txPending: Boolean, type: TransactionSummary.TransactionType) =
    setImageResource(
        if (txPending) {
            R.drawable.ic_tx_confirming
        } else {
            when (type) {
                TransactionSummary.TransactionType.DEPOSIT -> R.drawable.ic_tx_buy
                TransactionSummary.TransactionType.INTEREST_EARNED -> R.drawable.ic_tx_interest
                TransactionSummary.TransactionType.WITHDRAW -> R.drawable.ic_tx_sell
                else -> R.drawable.ic_tx_buy
            }
        }
    )

private fun TextView.setTxLabel(
    asset: AssetInfo,
    type: TransactionSummary.TransactionType
) {
    text = when (type) {
        TransactionSummary.TransactionType.DEPOSIT -> context.resources.getString(
            R.string.tx_title_transfer,
            asset.ticker
        )
        TransactionSummary.TransactionType.INTEREST_EARNED -> context.resources.getString(
            R.string.tx_title_interest,
            asset.ticker
        )
        TransactionSummary.TransactionType.WITHDRAW -> context.resources.getString(
            R.string.tx_title_withdraw,
            asset.ticker
        )
        else -> context.resources.getString(
            R.string.tx_title_transfer,
            asset.ticker
        )
    }
}

private fun TextView.setTxStatus(tx: CustodialInterestActivitySummaryItem) {
    text = when (tx.status) {
        InterestState.COMPLETE -> Date(tx.timeStampMs).toFormattedDate()
        InterestState.FAILED -> context.getString(R.string.activity_state_failed)
        InterestState.CLEARED -> context.getString(R.string.activity_state_cleared)
        InterestState.REFUNDED -> context.getString(R.string.activity_state_refunded)
        InterestState.PENDING -> context.getString(R.string.activity_state_pending)
        InterestState.PROCESSING -> context.getString(R.string.activity_state_pending)
        InterestState.MANUAL_REVIEW -> context.getString(R.string.activity_state_pending)
        InterestState.REJECTED -> context.getString(R.string.activity_state_rejected)
        InterestState.UNKNOWN -> context.getString(R.string.activity_state_unknown)
    }
}
