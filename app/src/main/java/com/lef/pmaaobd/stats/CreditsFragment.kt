package com.lef.pmaaobd.stats

import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

class CreditsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_credits, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val iconCredits = view.findViewById<LinearLayout>(R.id.ic_credits_list)
        resources.getStringArray(R.array.ic_credits_items).forEach {
            iconCredits.addView(TextView(requireContext()).apply {
                text = Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT)
                movementMethod = LinkMovementMethod.getInstance()
            })
        }
    }
}
