package com.example.skip.ui.about

internal enum class UpdateCardAction {
    Check,
    DownloadAndInstall,
    Install,
    None
}

internal object UpdateCardBehavior {
    fun stateAfterCheck(release: UpdateRelease, currentVersionName: String): UpdateCheckState {
        return if (VersionComparator.isNewer(release.tagName, currentVersionName)) {
            UpdateCheckState.Available(release)
        } else {
            UpdateCheckState.Latest(release)
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
