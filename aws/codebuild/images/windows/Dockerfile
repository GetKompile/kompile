# escape=`
ARG BASE_IMAGE=mcr.microsoft.com/windows/servercore:ltsc2022
FROM ${BASE_IMAGE}
SHELL ["powershell", "-NoProfile", "-Command", "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';"]
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
RUN Set-ExecutionPolicy Bypass -Scope Process -Force; [Net.ServicePointManager]::SecurityProtocol=[Net.ServicePointManager]::SecurityProtocol -bor 3072; Invoke-Expression ((New-Object Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1')); choco install -y git maven cmake protoc ccache strawberryperl python ruby 7zip msys2
RUN Invoke-WebRequest $env:GRAALVM_ARCHIVE_URL -OutFile C:\graal.zip; New-Item -ItemType Directory C:\opt\graalvm | Out-Null; 7z x C:\graal.zip -oC:\opt\graalvm -y | Out-Null; Invoke-WebRequest $env:JDK11_ARCHIVE_URL -OutFile C:\jdk.zip; New-Item -ItemType Directory C:\opt\jdk11 | Out-Null; 7z x C:\jdk.zip -oC:\opt\jdk11 -y | Out-Null
ENV GRAALVM_HOME="C:\opt\graalvm" JAVA11_HOME="C:\opt\jdk11"
