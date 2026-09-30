package io.github.maximemet.supercalc

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.maximemet.supercalc.databinding.ActivityAboutBinding

/**
 * 关于我们。参考实现里这是个独立 Activity（`AboutActivity`），
 * 我们保持一样：工具条上只有返回箭头和标题，内容居中排一行版本号和版权。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.aboutToolbar.pageTitle.setText(R.string.about_us)
        binding.aboutToolbar.btnPageBack.setOnClickListener { finish() }
        binding.tvAboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, 0, bars.right, 0)
            // 白色内容区让开导航栏，导航栏那一条由 nav_bar_scrim 画成纯黑
            binding.aboutContent.setPadding(0, 0, 0, bars.bottom)
            binding.navBarScrim.layoutParams =
                binding.navBarScrim.layoutParams.apply { height = bars.bottom }
            // 状态栏那条留给 root 的底色，工具条整体下移
            val toolbar = binding.aboutToolbar.root
            toolbar.layoutParams =
                (toolbar.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                    topMargin = bars.top
                }
            insets
        }
    }
}
