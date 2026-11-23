@echo off
chcp 65001 >nul
rem 뒷자리 맑음 — Pleos Connect v2.1.0 에뮬레이터
rem 먼저 Windows 「하이퍼바이저 플랫폼」이 켜져 있어야 하고, ANDROID_SDK_ROOT 에 Pleos 에뮬레이터 이미지가 설치된 SDK 를 지정한다.
rem 가상 기기(avd 이름 pleos)·캐시는 이 폴더의 tools\ 안에 둔다.
set H=%~dp0
if "%ANDROID_SDK_ROOT%"=="" ( echo ANDROID_SDK_ROOT 를 먼저 설정하세요 & exit /b 1 )
set ANDROID_HOME=%ANDROID_SDK_ROOT%
set ANDROID_USER_HOME=%H%tools\.android
set ANDROID_EMULATOR_HOME=%H%tools\.android
set ANDROID_AVD_HOME=%H%tools\avd
set TEMP=%H%tmp
set TMP=%H%tmp
"%ANDROID_SDK_ROOT%\emulator\emulator.exe" -avd pleos -no-snapshot-load -no-boot-anim -gpu host
