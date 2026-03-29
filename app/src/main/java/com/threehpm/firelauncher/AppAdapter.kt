package com.threehpm.firelauncher

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.threehpm.firelauncher.databinding.ItemAppBinding
import kotlin.math.roundToInt

class AppAdapter(
    private val items: List<AppItem>,
    private val onClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    inner class AppViewHolder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppItem) {
            binding.appTitle.text = item.title
            binding.appSubtitle.text = item.subtitle
            binding.appBadge.text = item.badge.uppercase()
            binding.appIcon.setImageDrawable(item.icon)
            bindAccent(item.accentColor)

            binding.root.setOnClickListener {
                onClick(item)
            }

            binding.root.setOnFocusChangeListener { view, hasFocus ->
                view.isSelected = hasFocus
                view.animate()
                    .scaleX(if (hasFocus) 1.05f else 1.0f)
                    .scaleY(if (hasFocus) 1.05f else 1.0f)
                    .translationY(if (hasFocus) -10f else 0f)
                    .setDuration(170L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                view.alpha = if (hasFocus) 1.0f else 0.94f
                view.translationZ = if (hasFocus) 28f else 0f
                binding.appSubtitle.alpha = if (hasFocus) 1.0f else 0.82f
                binding.appBadge.alpha = if (hasFocus) 1.0f else 0.9f
            }
        }

        private fun bindAccent(accentColor: Int) {
            binding.appAccent.background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(accentColor)
            }

            binding.appBadge.background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(withAlpha(accentColor, 0.16f))
                setStroke(1, withAlpha(accentColor, 0.45f))
            }
            binding.appBadge.setTextColor(ColorStateList.valueOf(accentColor))
        }

        private fun withAlpha(color: Int, factor: Float): Int {
            return Color.argb(
                (Color.alpha(color) * factor).roundToInt(),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}
