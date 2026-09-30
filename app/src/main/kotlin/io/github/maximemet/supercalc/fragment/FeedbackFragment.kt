package io.github.maximemet.supercalc.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.github.maximemet.supercalc.R
import io.github.maximemet.supercalc.databinding.FragmentFeedbackBinding

/**
 * 反馈页。
 *
 * 界面照参考实现，但提交按钮后面那套（友盟反馈服务）早就下线了，
 * 所以点「发送」只提示去 GitHub 提 issue。
 */
class FeedbackFragment : Fragment() {

    private var _binding: FragmentFeedbackBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentFeedbackBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.umengFbSend.setOnClickListener {
            Toast.makeText(requireContext(), R.string.feedback_offline, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}
