package com.example.skip.engine

import com.example.skip.model.MatchMode
import com.example.skip.model.RuleArea
import com.example.skip.model.RuleKind
import com.example.skip.model.RuleSource
import com.example.skip.model.SkipRule

object BuiltInPreciseRuleCatalog {
    const val VERSION = 1

    private val rules = listOf(
        SkipRule(
            id = "built_in_precise_com.duowan.kiwi_second_splash",
            source = RuleSource.BuiltIn,
            kind = RuleKind.Precise,
            name = "虎牙第二开屏跳过",
            packageName = "com.duowan.kiwi",
            appName = "虎牙直播",
            activityName = "com.duowan.kiwi.adsplash.view.SecondAdSplashActivity",
            matchTexts = listOf("跳过"),
            matchViewIds = listOf("com.duowan.kiwi:id/skip_time"),
            textMatchMode = MatchMode.Exact,
            contentDescriptionMatchMode = MatchMode.Exact,
            viewIdMatchMode = MatchMode.Exact,
            area = RuleArea.TopRight,
            priority = 200,
            cooldownMs = 1_500L,
            validDurationMs = 15_000L,
            minScore = 70,
            packageId = "built_in_precise_v1",
            createdAt = 0L
        )
    )

    fun forPackage(packageName: String): List<SkipRule> = rules.filter { it.packageName == packageName }
    fun all(): List<SkipRule> = rules
}
