package com.example.skip.util

import android.os.Build

object RomUtils {
    enum class RomType(val label: String) {
        HyperOS("HyperOS"),
        MIUI("MIUI"),
        OriginOS("OriginOS"),
        FuntouchOS("Funtouch OS"),
        ColorOS("ColorOS"),
        RealmeUI("realme UI"),
        MagicOS("MagicOS"),
        EMUI("EMUI"),
        Flyme("Flyme"),
        OneUI("One UI"),
        Unknown("未知系统")
    }

    data class DeviceInfo(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val androidVersion: String,
        val sdkInt: Int,
        val romType: RomType
    )

    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            romType = detectRom()
        )
    }

    fun detectRom(): RomType {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val display = Build.DISPLAY.orEmpty()
        val signals = listOf(
            manufacturer,
            brand,
            display,
            Build.PRODUCT.orEmpty(),
            Build.DEVICE.orEmpty(),
            Build.HARDWARE.orEmpty(),
            Build.ID.orEmpty(),
            Build.FINGERPRINT.orEmpty()
        ).map { it.lowercase() }

        return when {
            signals.containsAny("hyperos") -> RomType.HyperOS
            signals.containsAny("xiaomi", "redmi", "poco", "miui") -> RomType.MIUI
            signals.containsAny("originos", "origin os") -> RomType.OriginOS
            signals.containsAny("vivo", "iqoo", "funtouch") -> RomType.FuntouchOS
            signals.containsAny("realme") -> RomType.RealmeUI
            signals.containsAny("oppo", "oneplus", "coloros", "color os") -> RomType.ColorOS
            signals.containsAny("honor", "magicos", "magic os") -> RomType.MagicOS
            signals.containsAny("huawei", "emui", "harmony") -> RomType.EMUI
            signals.containsAny("flyme", "meizu") -> RomType.Flyme
            signals.containsAny("samsung", "oneui", "one ui") -> RomType.OneUI
            else -> RomType.Unknown
        }
    }

    fun guidanceFor(romType: RomType): List<String> {
        return when (romType) {
            RomType.HyperOS,
            RomType.MIUI -> listOf(
                "在系统设置中找到应用管理，进入 Skip。",
                "开启自启动或类似选项。",
                "电池策略选择“不限制”或类似选项。",
                "确认无障碍服务仍处于开启状态。",
                "如果服务经常失效，可尝试在最近任务中锁定本 App。"
            )
            RomType.OriginOS,
            RomType.FuntouchOS -> listOf(
                "进入设置中的应用与权限。",
                "确认无障碍服务已开启。",
                "在电池设置中允许后台运行。",
                "在后台高耗电管理中允许 Skip。",
                "如果仍失效，检查系统管家中的后台限制。"
            )
            RomType.ColorOS,
            RomType.RealmeUI -> listOf(
                "进入设置中的应用管理，找到 Skip。",
                "允许后台运行。",
                "关闭过度省电限制或选择类似的不限制选项。",
                "检查无障碍服务是否被系统关闭。"
            )
            RomType.MagicOS,
            RomType.EMUI -> listOf(
                "进入应用启动管理。",
                "将 Skip 设置为手动管理。",
                "允许自启动、关联启动和后台活动。",
                "检查电池优化与无障碍服务状态。"
            )
            RomType.Flyme -> listOf(
                "打开手机管家或系统设置。",
                "找到权限管理。",
                "允许后台运行并检查自启动。",
                "确认无障碍服务开启。"
            )
            RomType.OneUI -> listOf(
                "进入设置中的应用，找到 Skip。",
                "检查电池设置。",
                "允许后台使用。",
                "确认无障碍服务开启。"
            )
            RomType.Unknown -> listOf(
                "确认无障碍服务已开启。",
                "在电池或后台管理中允许 Skip 后台运行。",
                "如果系统提供自启动管理，请手动允许。",
                "不同系统菜单名称可能不同，请选择含义相近的选项。"
            )
        }
    }

    private fun List<String>.containsAny(vararg values: String): Boolean {
        return any { signal -> values.any { value -> signal.contains(value) } }
    }
}
