[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [ValidateRange(1, [int]::MaxValue)]
    [int]$VersionCode,

    # Required for a full release check; not needed with -CheckVersionOnly.
    [ValidatePattern('^[A-Fa-f0-9]{64}$')]
    [string]$Sha256,

    [string]$ApkPath,

    [switch]$AllowDirty,

    [switch]$VerifyGitHubRelease,

    # CI-friendly mode: verify that app/build.gradle.kts, README.md, docs/README.md and
    # RELEASE_NOTES.md agree on the version, without requiring the main branch, a clean worktree,
    # a built release APK or its SHA256.
    [switch]$CheckVersionOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-Git {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $output = @(& git @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') 执行失败：$($output -join [Environment]::NewLine)"
    }
    return $output
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Content,
        [Parameter(Mandatory = $true)][string]$Expected,
        [Parameter(Mandatory = $true)][string]$Label
    )

    # `String.Contains(String, StringComparison)` only exists on .NET Core / PowerShell 7+.
    # This script must also run under Windows PowerShell 5.1, so use the ordinal IndexOf overload,
    # which .NET Framework provides and which keeps the case-sensitive comparison.
    if ($Content.IndexOf($Expected, [System.StringComparison]::Ordinal) -lt 0) {
        throw "$Label 缺少：$Expected"
    }
}

$scriptDirectory = Split-Path -Parent $PSCommandPath
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory '..')).Path
$tagName = "v$Version"
$assetName = "Skip-v$Version-release.apk"
$downloadUrl = "https://github.com/mirad-tech/Skip/releases/download/$tagName/$assetName"

# The whole check runs inside one try/catch so the process exit code is deterministic: reporting a
# terminating error with `pwsh -File script.ps1` is not enough to fail a CI step on its own.
Push-Location $repoRoot
try {
    if (-not $CheckVersionOnly -and [string]::IsNullOrWhiteSpace($Sha256)) {
        throw '-Sha256 为必填参数（除非使用 -CheckVersionOnly）。'
    }
    $normalizedHash = if ([string]::IsNullOrWhiteSpace($Sha256)) { '' } else { $Sha256.ToUpperInvariant() }

    if (-not $CheckVersionOnly) {
        $branch = ((Invoke-Git -Arguments @('branch', '--show-current')) -join '').Trim()
        if ($branch -ne 'main') {
            throw "发布只能从 main 执行，当前分支：$branch"
        }

        $status = @(Invoke-Git -Arguments @('status', '--porcelain=v1'))
        if (-not $AllowDirty -and $status.Count -gt 0) {
            throw "工作区不干净：$($status -join [Environment]::NewLine)"
        }

        if (-not $AllowDirty) {
            $head = ((Invoke-Git -Arguments @('rev-parse', 'HEAD')) -join '').Trim()
            $originMain = ((Invoke-Git -Arguments @('rev-parse', 'origin/main')) -join '').Trim()
            if ($head -ne $originMain) {
                throw "本地 main 与 origin/main 不一致：HEAD=$head origin/main=$originMain"
            }
        }
    }

    $gradle = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'app/build.gradle.kts'))
    Assert-Contains -Content $gradle -Expected "versionCode = $VersionCode" -Label 'app/build.gradle.kts'
    Assert-Contains -Content $gradle -Expected "versionName = `"$Version`"" -Label 'app/build.gradle.kts'

    $readme = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'README.md'))
    $docsReadme = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'docs/README.md'))
    foreach ($document in @(
        @{ Label = 'README.md'; Content = $readme },
        @{ Label = 'docs/README.md'; Content = $docsReadme }
    )) {
        Assert-Contains -Content $document.Content -Expected "当前源码版本：``$Version``" -Label $document.Label
        Assert-Contains -Content $document.Content -Expected $downloadUrl -Label $document.Label
        if (-not $CheckVersionOnly) {
            Assert-Contains -Content $document.Content -Expected "SHA256：``$normalizedHash``" -Label $document.Label
        }
    }

    $releaseNotes = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'RELEASE_NOTES.md'))
    $escapedVersion = [regex]::Escape($Version)
    $sectionMatch = [regex]::Match(
        $releaseNotes,
        "(?ms)^##\s+$escapedVersion\s*\r?\n(.*?)(?=^##\s+|\z)"
    )
    if (-not $sectionMatch.Success) {
        throw "RELEASE_NOTES.md 缺少 $Version 章节"
    }
    $releaseSection = $sectionMatch.Value
    Assert-Contains -Content $releaseSection -Expected $assetName -Label "RELEASE_NOTES.md $Version"
    Assert-Contains -Content $releaseSection -Expected 'versionCode' -Label "RELEASE_NOTES.md $Version"
    if (-not $CheckVersionOnly) {
        Assert-Contains -Content $releaseSection -Expected $normalizedHash -Label "RELEASE_NOTES.md $Version"
    }
    if ($releaseSection -match '尚未创建|尚未上传|尚未记录') {
        throw "RELEASE_NOTES.md $Version 仍包含未发布占位状态"
    }

    if ($CheckVersionOnly) {
        Write-Output "Version metadata verified: $Version ($VersionCode)"
    } else {
        $metadataPath = Join-Path $repoRoot 'app/build/outputs/apk/release/output-metadata.json'
        if (-not (Test-Path -LiteralPath $metadataPath)) {
            throw "缺少 Release 构建元数据：$metadataPath"
        }
        $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
        $element = @($metadata.elements)[0]
        if ([int]$element.versionCode -ne $VersionCode -or [string]$element.versionName -ne $Version) {
            throw "Release 构建元数据版本不一致：$($element.versionName) ($($element.versionCode))"
        }

        if ([string]::IsNullOrWhiteSpace($ApkPath)) {
            $ApkPath = Join-Path $repoRoot "downloads/$assetName"
        } elseif (-not [System.IO.Path]::IsPathRooted($ApkPath)) {
            $ApkPath = Join-Path $repoRoot $ApkPath
        }
        $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
        if ((Split-Path -Leaf $resolvedApk) -ne $assetName) {
            throw "APK 文件名必须为 $assetName，当前为 $(Split-Path -Leaf $resolvedApk)"
        }
        $actualHash = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($actualHash -ne $normalizedHash) {
            throw "APK SHA256 不一致：expected=$normalizedHash actual=$actualHash"
        }

        $trackedSigningFiles = @(Invoke-Git -Arguments @('ls-files', '--', 'keystore.properties', 'release.keystore'))
        if ($trackedSigningFiles.Count -gt 0) {
            throw "签名文件被 Git 跟踪：$($trackedSigningFiles -join ', ')"
        }

        if ($VerifyGitHubRelease) {
            $releaseJson = & gh release view $tagName --repo mirad-tech/Skip --json tagName,isDraft,isPrerelease,assets,url
            if ($LASTEXITCODE -ne 0) {
                throw "无法读取 GitHub Release $tagName"
            }
            $release = $releaseJson | ConvertFrom-Json
            if ($release.tagName -ne $tagName -or $release.isDraft -or $release.isPrerelease) {
                throw "GitHub Release 状态不正确：tag=$($release.tagName) draft=$($release.isDraft) prerelease=$($release.isPrerelease)"
            }
            $asset = @($release.assets) | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
            if ($null -eq $asset) {
                throw "GitHub Release 缺少资产：$assetName"
            }
            $remoteDigest = ([string]$asset.digest -replace '^sha256:', '').ToUpperInvariant()
            if ($remoteDigest -ne $normalizedHash) {
                throw "GitHub Release 资产 SHA256 不一致：expected=$normalizedHash actual=$remoteDigest"
            }

            $tagCommit = ((Invoke-Git -Arguments @('rev-list', '-n', '1', $tagName)) -join '').Trim()
            $mainCommit = ((Invoke-Git -Arguments @('rev-parse', 'main')) -join '').Trim()
            if ($tagCommit -ne $mainCommit) {
                throw "标签未指向当前 main：tag=$tagCommit main=$mainCommit"
            }

            $latestJson = & gh release list --repo mirad-tech/Skip --limit 1 --json tagName,isLatest
            if ($LASTEXITCODE -ne 0) {
                throw '无法读取 GitHub 最新 Release'
            }
            $latest = @($latestJson | ConvertFrom-Json)[0]
            if ($latest.tagName -ne $tagName -or -not $latest.isLatest) {
                throw "GitHub Latest Release 不是 $tagName"
            }
        }

        Write-Output "Release metadata verified: $Version ($VersionCode)"
        Write-Output "APK: $resolvedApk"
        Write-Output "SHA256: $normalizedHash"
        if ($VerifyGitHubRelease) {
            Write-Output "GitHub Release verified: https://github.com/mirad-tech/Skip/releases/tag/$tagName"
        }
    }

    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally {
    Pop-Location
}
