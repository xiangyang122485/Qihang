@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ============================================================
echo   Qihang 一键发布到 GitHub（源码 + Release APK）
echo   本脚本在你的电脑上运行（直连 GitHub），只需粘贴一次令牌
echo ============================================================
echo.

where git >nul 2>&1
if errorlevel 1 (
  echo [错误] 未找到 git。请先安装 Git for Windows：https://git-scm.com/downloads
  pause
  exit /b 1
)

if not exist "app\build\outputs\apk\release\app-release.apk" (
  echo [错误] 找不到 Release APK：app\build\outputs\apk\release\app-release.apk
  pause
  exit /b 1
)

set /p TOKEN=请粘贴你的 GitHub Personal Access Token（在 github.com/settings/tokens 生成，勾选 repo 全选）：

REM 用带令牌的 HTTPS 地址临时推送（推送后立刻还原，令牌不留在配置里）
git remote set-url origin https://xiangyang122485:%TOKEN%@github.com/xiangyang122485/Qihang.git

echo.
echo [1/2] 正在推送源代码到 GitHub ...
git -c credential.helper= push -u origin master
if errorlevel 1 (
  echo.
  echo [失败] 源码推送失败。常见原因：
  echo   1) 仓库 "Qihang" 不存在 —— 请先在 GitHub 新建名为 Qihang 的空仓库（不要勾 Add README）。
  echo   2) 令牌权限不足 —— 需在 github.com/settings/tokens 勾选 repo（全选 repo 即可）。
  echo   3) 仓库非空 —— 若已含文件，请删空后重试。
  git remote set-url origin https://github.com/xiangyang122485/Qihang.git
  pause
  exit /b 1
)

REM 还原 remote，去掉令牌
git remote set-url origin https://github.com/xiangyang122485/Qihang.git
echo [OK] 源码推送成功！

echo.
echo [2/2] 正在创建 Release 并上传 APK ...

(
echo try {
echo   $t = $env:TOKEN
echo   $api = 'https://api.github.com/repos/xiangyang122485/Qihang/releases'
echo   $body = @{tag_name='v1.0.0'; name='Qihang v1.0.0'; body='Qihang downloader - first release (app-release.apk)'} ^| ConvertTo-Json
echo   $r = Invoke-RestMethod -Uri $api -Method Post -Headers @{Authorization='token '+$t} -ContentType 'application/json' -Body $body
echo   $up = $r.upload_url -replace '\{\?name,label\}',''
echo   $f = 'app/build/outputs/apk/release/app-release.apk'
echo   Invoke-RestMethod -Uri ($up + '?name=app-release.apk') -Method Post -Headers @{Authorization='token '+$t} -ContentType 'application/vnd.android.package-archive' -InFile $f
echo   Write-Host 'Release 与 APK 上传完成'
echo } catch {
echo   Write-Host '创建 Release 失败（可能 v1.0.0 已存在或令牌权限不足）。请到 https://github.com/xiangyang122485/Qihang/releases/new 手动上传 app-release.apk'
echo }
) > "%TEMP%\upload_release.ps1"

powershell -NoProfile -ExecutionPolicy Bypass -File "%TEMP%\upload_release.ps1"

echo.
echo ============================================================
echo  全部完成！
echo  打开 https://github.com/xiangyang122485/Qihang 查看你的项目。
echo ============================================================
pause
endlocal
