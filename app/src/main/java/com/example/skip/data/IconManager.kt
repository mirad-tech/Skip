package com.example.skip.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.example.skip.R

object IconManager {
    val homeImageRes: Int = R.drawable.ic_skip_wordmark

    val schemes = listOf(
        IconScheme(
            id = "sky_blue",
            name = "Sky Blue",
            aliasClassName = "com.example.skip.icon.SkyBlueIcon",
            iconRes = R.mipmap.ic_launcher_sky_blue,
            previewColor = 0xFF2F7CF6.toInt()
        ),
        IconScheme(
            id = "mint_green",
            name = "Mint Green",
            aliasClassName = "com.example.skip.icon.MintGreenIcon",
            iconRes = R.mipmap.ic_launcher_mint_green,
            previewColor = 0xFF1FAF7A.toInt()
        ),
        IconScheme(
            id = "violet_glow",
            name = "Violet Glow",
            aliasClassName = "com.example.skip.icon.VioletGlowIcon",
            iconRes = R.mipmap.ic_launcher_violet_glow,
            previewColor = 0xFF7C4DFF.toInt()
        ),
        IconScheme(
            id = "sunset_orange",
            name = "Sunset Orange",
            aliasClassName = "com.example.skip.icon.SunsetOrangeIcon",
            iconRes = R.mipmap.ic_launcher_sunset_orange,
            previewColor = 0xFFFF7A1A.toInt()
        ),
        IconScheme(
            id = "cherry_pink",
            name = "Cherry Pink",
            aliasClassName = "com.example.skip.icon.CherryPinkIcon",
            iconRes = R.mipmap.ic_launcher_cherry_pink,
            previewColor = 0xFFE83F7D.toInt()
        ),
        IconScheme(
            id = "obsidian_gold",
            name = "Obsidian Gold",
            aliasClassName = "com.example.skip.icon.ObsidianGoldIcon",
            iconRes = R.mipmap.ic_launcher_obsidian_gold,
            previewColor = 0xFF1D1A16.toInt()
        )
    )

    fun currentScheme(context: Context): IconScheme {
        val id = SettingsRepository.getIconScheme(context)
        return schemes.firstOrNull { it.id == id } ?: schemes.first()
    }

    fun applyScheme(context: Context, scheme: IconScheme): Boolean {
        return runCatching {
            setLauncherAliasState(context, scheme)
            SettingsRepository.setIconScheme(context, scheme.id)
        }.isSuccess
    }

    fun syncCurrentScheme(context: Context): Boolean {
        return runCatching {
            setLauncherAliasState(context, currentScheme(context))
        }.isSuccess
    }

    private fun setLauncherAliasState(context: Context, scheme: IconScheme) {
        val packageManager = context.packageManager
        packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, scheme.aliasClassName),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        schemes.filterNot { it.id == scheme.id }.forEach { other ->
            packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, other.aliasClassName),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}

data class IconScheme(
    val id: String,
    val name: String,
    val aliasClassName: String,
    val iconRes: Int,
    val previewColor: Int
)
