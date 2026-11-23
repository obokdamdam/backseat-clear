# 뒷자리 맑음 빌드 — 캐시·임시 파일은 모두 이 폴더 안에만 둔다.
# 먼저 JAVA_HOME(JDK 21) · ANDROID_HOME(Android SDK 36) 을 설정하고, Gradle 8.14.3 이 PATH 에 있거나 GRADLE_BIN 으로 지정되어 있어야 한다.
# 쓰는 법: powershell -File .\build.ps1 testDebugUnitTest assembleDebug
$R = $PSScriptRoot
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { Write-Error "JAVA_HOME 과 ANDROID_HOME 을 먼저 설정하세요"; exit 1 }
if (-not $env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME = "$R\tools\.android" }
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = "$R\tools\gradle-home" }
$env:TEMP = "$R\tmp"
$env:TMP = "$R\tmp"
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$R\tmp -Dfile.encoding=UTF-8"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
New-Item -ItemType Directory -Force "$R\tmp" | Out-Null
Set-Location $R
$G = if ($env:GRADLE_BIN) { $env:GRADLE_BIN } else { "gradle" }
& $G --no-daemon --console=plain @args
exit $LASTEXITCODE
