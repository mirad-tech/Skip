package com.example.skip.ui.about

internal enum class UpdateCardAction {
    Check,
    DownloadAndInstall,
    Install,
    None
}

internal object UpdateCardBehavior {
    fun stateAfterCheck(release: UpdateRelease, currentVersionName: String): UpdateCheckState {
        return when (val comparison = VersionComparator.compare(release.tagName, currentVersionName)) {
            is VersionComparison.Parsed -> if (comparison.isNewer) {
                UpdateCheckState.Available(release)
            } else {
                UpdateCheckState.Latest(release)
            }

            is VersionComparison.Unparseable -> UpdateCheckState.Error(
                message = "${comparison.source}无法解析：${comparison.value}",
                release = release
            )
        }
    }

    fun nextActionFor(state: UpdateCheckState): UpdateCardAction {
        return when (state) {
            UpdateCheckState.Idle,
            is UpdateCheckState.Latest,
            is UpdateCheckState.Error -> UpdateCardAction.Check
            is UpdateCheckState.Available -> UpdateCardAction.DownloadAndInstall
            is UpdateCheckState.Downloaded,
            is UpdateCheckState.InstallPermissionNeeded -> UpdateCardAction.Install
            UpdateCheckState.Checking,
            is UpdateCheckState.Downloading -> UpdateCardAction.None
        }
    }
}
