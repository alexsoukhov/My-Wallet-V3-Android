package piuk.blockchain.android.ui.customviews

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import piuk.blockchain.android.R
import piuk.blockchain.android.ui.resources.AssetResources
import piuk.blockchain.android.coincore.TrendingPair
import piuk.blockchain.android.databinding.ItemTrendingPairRowBinding
import piuk.blockchain.android.databinding.ViewTrendingPairsBinding
import piuk.blockchain.android.util.context
import piuk.blockchain.android.util.getResolvedDrawable
import piuk.blockchain.android.util.gone
import piuk.blockchain.android.util.visible

class TrendingPairsView(
    context: Context,
    attrs: AttributeSet
) : ConstraintLayout(context, attrs) {

    private val binding: ViewTrendingPairsBinding =
        ViewTrendingPairsBinding.inflate(LayoutInflater.from(context), this, true)
    private var viewType: TrendingType = TrendingType.OTHER

    init {
        setupView(context, attrs)
        binding.trendingList.addItemDecoration(
            BlockchainListDividerDecor(context)
        )
    }

    fun initialise(
        pairs: List<TrendingPair>,
        onSwapPairClicked: (TrendingPair) -> Unit,
        assetResources: AssetResources
    ) {
        setupPairs(pairs, onSwapPairClicked, assetResources)
    }

    private fun setupView(context: Context, attrs: AttributeSet) {
        val attributes = context.obtainStyledAttributes(attrs, R.styleable.TrendingPairsView, 0, 0)

        viewType =
            TrendingType.fromInt(attributes.getInt(R.styleable.TrendingPairsView_trending_type, 1))

        attributes.recycle()
    }

    private fun setupPairs(
        pairs: List<TrendingPair>,
        onSwapPairClicked: (TrendingPair) -> Unit,
        assetResources: AssetResources
    ) {
        with(binding) {
            if (pairs.isEmpty()) {
                trendingEmpty.visible()
                trendingList.gone()
            } else {
                trendingEmpty.gone()
                trendingList.apply {
                    layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
                    adapter = TrendingPairsAdapter(
                        type = viewType,
                        itemClicked = {
                            onSwapPairClicked(it)
                        },
                        items = pairs,
                        assetResources = assetResources
                    )
                    visible()
                }
            }
        }
    }

    enum class TrendingType {
        SWAP,
        OTHER;

        companion object {
            fun fromInt(value: Int) = values()[value]
        }
    }
}

private class TrendingPairsAdapter(
    val type: TrendingPairsView.TrendingType,
    val itemClicked: (TrendingPair) -> Unit,
    private val items: List<TrendingPair>,
    private val assetResources: AssetResources
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        TrendingPairViewHolder(
            ItemTrendingPairRowBinding.inflate(LayoutInflater.from(parent.context), parent, false), itemClicked
        )

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder as TrendingPairViewHolder).bind(type, items[position], assetResources)
    }

    override fun getItemCount(): Int = items.size

    private class TrendingPairViewHolder(
        private val binding: ItemTrendingPairRowBinding,
        val itemClicked: (TrendingPair) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(type: TrendingPairsView.TrendingType, item: TrendingPair, assetResources: AssetResources) {
            binding.apply {
                assetResources.loadAssetIcon(trendingIconIn, item.sourceAccount.asset)
                assetResources.loadAssetIcon(trendingIconOut, item.destinationAccount.asset)
                if (item.enabled) {
                    trendingRoot.setOnClickListener {
                        itemClicked(item)
                    }
                    trendingRoot.alpha = 1f
                } else {
                    trendingRoot.setOnClickListener(null)
                    trendingRoot.alpha = 0.6f
                }

                when (type) {
                    TrendingPairsView.TrendingType.SWAP -> {
                        trendingTitle.text = context.getString(
                            R.string.trending_swap,
                            item.sourceAccount.asset.name
                        )
                        trendingSubtitle.text = context.getString(
                            R.string.trending_receive,
                            item.destinationAccount.asset.name
                        )
                        trendingIconType.setImageDrawable(context.getResolvedDrawable(R.drawable.ic_swap_light_blue))
                    }
                    else -> {
                        // do nothing
                    }
                }
            }
        }
    }
}
