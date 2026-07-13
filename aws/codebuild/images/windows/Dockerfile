# escape=`
ARG BASE_IMAGE=mcr.microsoft.com/windows/servercore:ltsc2022
FROM ${BASE_IMAGE}
SHELL ["powershell", "-NoProfile", "-Command", "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';"]
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
RUN Set-ExecutionPolicy Bypass -Scope Process -Force; [Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor 3072; Invoke-Expression ((New-Object Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1')); choco install -y git maven cmake protoc ccache strawberryperl python ruby 7zip msys2
# MSYS2 MINGW64 toolchain (mirrors the DL4J Windows workflows' pacman list):
# gcc/gfortran/nasm/ragel/SDL2 + Vulkan headers/loader for the native build.
RUN $p='C:\tools\msys64\usr\bin\pacman.exe'; & $p -Syu --noconfirm; & $p -Syuu --noconfirm; & $p -S --needed --noconfirm mingw-w64-x86_64-toolchain mingw-w64-x86_64-gcc mingw-w64-x86_64-gcc-fortran mingw-w64-x86_64-cmake mingw-w64-x86_64-make mingw-w64-x86_64-libtool mingw-w64-x86_64-libwinpthread-git mingw-w64-x86_64-nasm mingw-w64-x86_64-ragel mingw-w64-x86_64-pkg-config mingw-w64-x86_64-gnupg mingw-w64-x86_64-SDL2 mingw-w64-x86_64-vulkan-headers mingw-w64-x86_64-vulkan-loader; if ($LASTEXITCODE -ne 0) { throw 'pacman toolchain install failed' }
RUN Invoke-WebRequest $env:GRAALVM_ARCHIVE_URL -OutFile C:\graal.zip; New-Item -ItemType Directory C:\opt\graalvm | Out-Null; 7z x C:\graal.zip -oC:\opt\graalvm -y | Out-Null; Invoke-WebRequest $env:JDK11_ARCHIVE_URL -OutFile C:\jdk.zip; New-Item -ItemType Directory C:\opt\jdk11 | Out-Null; 7z x C:\jdk.zip -oC:\opt\jdk11 -y | Out-Null
ENV GRAALVM_HOME="C:\opt\graalvm" JAVA11_HOME="C:\opt\jdk11" MSYSTEM="MINGW64" PATH="C:\tools\msys64\mingw64\bin;C:\Program Files\Git\bin;C:\opt\graalvm\bin;C:\Windows\system32;C:\Windows"
