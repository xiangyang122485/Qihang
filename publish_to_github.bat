@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "REPO=xiangyang122485/Qihang"
set "APK=app\build\outputs\apk\release\app-release.apk"
set "ASSET=app-release.apk"

echo ============================================================
echo   Qihang 发布：推送源码分支并上传 Release APK
echo   令牌仅经环境变量传递，不写入 remote URL、.git/config 或磁盘
echo ============================================================
echo.

where git >nul 2>&1
if errorlevel 1 (
  echo [错误] 未找到 git。请安装 Git for Windows：https://git-scm.com/downloads
  pause
  exit /b 1
)

if not exist "%APK%" (
  echo [错误] 找不到 Release APK：%APK%
  echo         请先执行 gradlew assembleRelease
  pause
  exit /b 1
)

REM 版本号取自 app\build.gradle.kts 的 versionName，避免与界面显示漂移
set "VER="
for /f "tokens=3 delims= " %%v in ('findstr /r /c:"^ *versionName = " app\build.gradle.kts') do set "VER=%%~v"
if not defined VER (
  echo [错误] 无法从 app\build.gradle.kts 解析 versionName
  pause
  exit /b 1
)
set "TAG=v%VER%"
set "NOTE=Qihang %VER%"

REM 推送目标为当前分支，不再固定 master
set "BR="
for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD') do set "BR=%%b"
if "%BR%"=="HEAD" (
  echo [错误] 当前处于分离 HEAD，请先检出目标分支
  pause
  exit /b 1
)

REM 令牌优先取环境变量，未设置时再交互输入
set "TOKEN="
if defined GH_TOKEN set "TOKEN=!GH_TOKEN!"
if defined GH_PAT set "TOKEN=!GH_PAT!"
if not defined TOKEN set /p "TOKEN=粘贴 GitHub Personal Access Token（需 repo 权限）："
if not defined TOKEN (
  echo [错误] 未提供令牌
  pause
  exit /b 1
)
set "GH_TOKEN="
set "GH_PAT="

REM askpass 脚本本身不含令牌，运行时从环境变量回显
set "ASKPASS=%TEMP%\gh_dl_askpass.cmd"
> "%ASKPASS%" echo @echo off
>> "%ASKPASS%" echo echo %%TOKEN%%
>> "%ASKPASS%" echo exit /b 0
set "GIT_ASKPASS=%ASKPASS%"
set "GIT_TERMINAL_PROMPT=0"

REM remote 固定为不含凭据的 HTTPS 地址
set "URL=https://github.com/%REPO%.git"
git remote get-url origin >nul 2>&1
if errorlevel 1 (
  git remote add origin "%URL%"
) else (
  git remote set-url origin "%URL%"
)

echo [1/2] 推送分支 %BR% 到 %REPO% ...
git -c credential.helper= push -u origin "%BR%"
if errorlevel 1 goto :push_failed

echo [OK] 源码推送完成

echo.
echo [2/2] 创建 Release %TAG% 并上传 %ASSET% ...
set "PS1=%TEMP%\gh_dl_release.ps1"
(
echo try {
echo   $h = @{ Authorization = ('token ' + $env:TOKEN); Accept = 'application/vnd.github+json' }
echo   $api = ('https://api.github.com/repos/' + $env:REPO + '/releases')
echo   $rel = $null
echo   try { $rel = Invoke-RestMethod -Uri ($api + '/tags/' + $env:TAG) -Headers $h } catch { $rel = $null }
echo   if (-not $rel) {
echo     $b = @{ tag_name = $env:TAG; name = $env:TAG; body = $env:NOTE } ^| ConvertTo-Json
echo     $rel = Invoke-RestMethod -Uri $api -Method Post -Headers $h -ContentType 'application/json' -Body $b
echo   }
echo   foreach ($a in $rel.assets) {
echo     if ($a.name -eq $env:ASSET) { Invoke-RestMethod -Uri $a.url -Method Delete -Headers $h ^| Out-Null }
echo   }
echo   $up = $rel.upload_url -replace '\{\?name,label\}', ''
echo   Invoke-RestMethod -Uri ($up + '?name=' + $env:ASSET) -Method Post -Headers $h -ContentType 'application/vnd.android.package-archive' -InFile $env:APK ^| Out-Null
echo   Write-Host ('Release ' + $env:TAG + ' 与 APK 上传完成')
echo   exit 0
echo } catch {
echo   Write-Host ('Release 上传失败：' + $_.ErrorDetails.Message + ' ' + $_.Exception.Message)
echo   Write-Host ('可手动上传：https://github.com/' + $env:REPO + '/releases/new?tag=' + $env:TAG)
echo   exit 1
echo }
) > "%PS1%"

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
if errorlevel 1 goto :release_failed

del /f /q "%ASKPASS%" >nul 2>&1
del /f /q "%PS1%" >nul 2>&1
set "TOKEN="
echo.
echo ============================================================
echo  完成：https://github.com/%REPO%/releases/tag/%TAG%
echo ============================================================
pause
endlocal
exit /b 0

:push_failed
del /f /q "%ASKPASS%" >nul 2>&1
set "TOKEN="
echo.
echo [失败] 源码推送失败。可能原因：
echo   1) 仓库不存在或令牌缺少 repo 权限。
echo   2) 远端分支领先本地，需先 git pull --rebase。
echo   3) 令牌已失效或为 fine-grained 且未授权目标仓库。
pause
endlocal
exit /b 1

:release_failed
del /f /q "%ASKPASS%" >nul 2>&1
del /f /q "%PS1%" >nul 2>&1
set "TOKEN="
echo.
echo [失败] Release 上传未完成，源码已推送。请按上方提示手动上传。
pause
endlocal
exit /b 1
