package piuk.blockchain.android.ui.upgrade

import android.content.Context
import android.databinding.DataBindingUtil
import android.os.Bundle
import android.support.annotation.StringRes
import android.support.v4.content.ContextCompat
import android.support.v4.view.PagerAdapter
import android.support.v4.view.ViewPager
import android.support.v7.app.AlertDialog
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import piuk.blockchain.android.R
import piuk.blockchain.android.databinding.ActivityUpgradeWalletBinding
import com.blockchain.ui.password.SecondPasswordHandler
import org.koin.android.ext.android.inject
import piuk.blockchain.androidcoreui.ui.base.BaseMvpActivity
import com.blockchain.ui.dialog.MaterialProgressDialog
import piuk.blockchain.androidcoreui.ui.customviews.ToastCustom

internal class UpgradeWalletActivity : BaseMvpActivity<UpgradeWalletView, UpgradeWalletPresenter>(),
    UpgradeWalletView, ViewPager.OnPageChangeListener {

    private lateinit var binding: ActivityUpgradeWalletBinding
    private var progressDialog: MaterialProgressDialog? = null

    private val upgradeWalletPresenter: UpgradeWalletPresenter by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.activity_upgrade_wallet)

        binding.upgradePageHeader.setFactory {
            val textView = TextView(this)
            textView.gravity = Gravity.CENTER
            textView.textSize = 14f
            textView.setTextColor(ContextCompat.getColor(this, R.color.primary_navy_medium))
            textView
        }

        binding.upgradePageHeader.inAnimation = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        binding.upgradePageHeader.outAnimation = AnimationUtils.loadAnimation(this, R.anim.fade_out)
        binding.upgradePageHeader.setText(resources.getString(R.string.upgrade_page_1))

        val adapter = CustomPagerAdapter(this)
        binding.pager.adapter = adapter
        binding.pager.addOnPageChangeListener(this)

        binding.upgradeBtn.setOnClickListener { upgradeClicked() }

        onViewReady()
    }

    override fun createPresenter(): UpgradeWalletPresenter? {
        return upgradeWalletPresenter
    }

    override fun getView(): UpgradeWalletView {
        return this
    }

    override fun showChangePasswordDialog() {
        val pwLayout = layoutInflater.inflate(R.layout.modal_change_password, null) as LinearLayout

        AlertDialog.Builder(this, R.style.AlertDialogStyle)
            .setTitle(R.string.app_name)
            .setMessage(R.string.weak_password)
            .setCancelable(false)
            .setView(pwLayout)
            .setPositiveButton(R.string.yes) { _, _ ->
                val password1 = (pwLayout.findViewById<View>(R.id.pw1) as EditText).text.toString()
                val password2 = (pwLayout.findViewById<View>(R.id.pw2) as EditText).text.toString()
                presenter.submitPasswords(password1, password2)
            }
            .setNegativeButton(R.string.no) { dialog, whichButton ->
                showToast(R.string.password_unchanged,
                    ToastCustom.TYPE_GENERAL)
            }
            .show()
    }

    override fun showToast(@StringRes message: Int, @ToastCustom.ToastType type: String) {
        ToastCustom.makeText(this, getString(message), ToastCustom.LENGTH_SHORT, type)
    }

    override fun onUpgradeStarted() {
        binding.upgradePageTitle.text = getString(R.string.upgrading)
        binding.upgradePageHeader.setText(getString(R.string.upgrading_started_info))
        binding.progressBar.visibility = View.VISIBLE
        binding.pager.visibility = View.GONE
        binding.upgradeActionContainer.visibility = View.GONE
    }

    override fun onUpgradeCompleted() {
        binding.upgradePageTitle.text = getString(R.string.upgrade_success_heading)
        binding.upgradePageHeader.setText(getString(R.string.upgrade_success_info))
        binding.progressBar.visibility = View.GONE
        binding.btnUpgradeComplete.visibility = View.VISIBLE
        binding.btnUpgradeComplete.setOnClickListener { presenter.onContinueClicked() }
    }

    override fun onUpgradeFailed() {
        binding.upgradePageTitle.text = getString(R.string.upgrade_fail_heading)
        binding.upgradePageHeader.setText(getString(R.string.upgrade_fail_info))
        binding.progressBar.visibility = View.GONE
        binding.btnUpgradeComplete.visibility = View.VISIBLE
        binding.btnUpgradeComplete.text = getString(R.string.CLOSE)
        binding.btnUpgradeComplete.setOnClickListener { onBackPressed() }
    }

    override fun onBackButtonPressed() {
        super.onBackPressed()
    }

    override fun onBackPressed() {
        presenter.onBackButtonPressed()
    }

    override fun showProgressDialog(@StringRes message: Int) {
        progressDialog = MaterialProgressDialog(this)
        progressDialog?.setCancelable(false)
        progressDialog?.setMessage(message)
        progressDialog?.show()
    }

    override fun dismissProgressDialog() {
        if (progressDialog != null && progressDialog!!.isShowing) {
            progressDialog?.dismiss()
            progressDialog = null
        }
    }

    override fun onPageSelected(position: Int) {
        setSelectedPage(position)
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
        // No-op
    }

    override fun onPageScrollStateChanged(state: Int) {
        // No-op
    }

    private fun upgradeClicked() {
        secondPasswordHandler.validate(object : SecondPasswordHandler.ResultListener {
            override fun onNoSecondPassword() {
                presenter.onUpgradeRequested(null)
            }

            override fun onSecondPasswordValidated(validatedSecondPassword: String) {
                presenter.onUpgradeRequested(validatedSecondPassword)
            }
        })
    }

    private fun setSelectedPage(position: Int) {
        when (position) {
            0 -> {
                binding.upgradePageHeader.setText(resources.getString(R.string.upgrade_page_1))
                setBackground(binding.pageBox0, R.drawable.rounded_view_accent_blue)
                setBackground(binding.pageBox1, R.drawable.rounded_view_dark_blue)
                setBackground(binding.pageBox2, R.drawable.rounded_view_dark_blue)
            }
            1 -> {
                binding.upgradePageHeader.setText(resources.getString(R.string.upgrade_page_2))
                setBackground(binding.pageBox0, R.drawable.rounded_view_dark_blue)
                setBackground(binding.pageBox1, R.drawable.rounded_view_accent_blue)
                setBackground(binding.pageBox2, R.drawable.rounded_view_dark_blue)
            }
            2 -> {
                binding.upgradePageHeader.setText(resources.getString(R.string.upgrade_page_3))
                setBackground(binding.pageBox0, R.drawable.rounded_view_dark_blue)
                setBackground(binding.pageBox1, R.drawable.rounded_view_dark_blue)
                setBackground(binding.pageBox2, R.drawable.rounded_view_accent_blue)
            }
        }
    }

    private fun setBackground(view: View, res: Int) {
        view.background = ContextCompat.getDrawable(this, res)
    }

    private class CustomPagerAdapter internal constructor(private val context: Context) : PagerAdapter() {
        private val inflater: LayoutInflater =
            this.context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        private val resources =
            intArrayOf(R.drawable.upgrade_backup, R.drawable.upgrade_hd, R.drawable.upgrade_balance)

        override fun getCount(): Int {
            return resources.size
        }

        override fun isViewFromObject(view: View, item: Any): Boolean {
            return view == item
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val itemView =
                inflater.inflate(
                    R.layout.activity_upgrade_wallet_pager_item,
                    container,
                    false
                )

            val imageView = itemView.findViewById<View>(R.id.imageView) as ImageView
            imageView.setImageResource(resources[position])

            container.addView(itemView)

            return itemView
        }

        override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
            (item as? LinearLayout)?.let {
                container.removeView(it)
            }
        }
    }
}