#Requires -Version 5.1
<#
.SYNOPSIS
    环境检查脚本 - 检查 Java/Maven/JAR/config.yml 是否就绪，缺失则自动修复。
.DESCRIPTION
    四阶段检查：
    1. Java >= 17
    2. Maven
    3. target\postgres-router.jar
    4. config.yml
#>

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$script:ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$script:JavaOk = $false
$script:MavenOk = $false
$script:JarOk = $false
$script:ConfigOk = $false

function Write-Status {
    param([string]$Msg, [string]$Color = "White")
    Write-Host "[$(Get-Date -Format 'HH:mm:ss')] " -NoNewline -ForegroundColor Gray
    Write-Host $Msg -ForegroundColor $Color
}

function Test-Java {
    Write-Host "`n===== [1/4] 检查 Java 环境 =====" -ForegroundColor Cyan
    try {
        $versionOutput = & java -version 2>&1
        $versionString = ($versionOutput | Out-String).Trim()
        Write-Host "输出: $versionString" -ForegroundColor Gray

        if ($versionString -match '"(\d+)(?:\.(\d+))?') {
            $major = [int]$Matches[1]
            if ($major -ge 17) {
                Write-Status "Java 版本: $major >= 17，通过" "Green"
                $script:JavaOk = $true
                return
            }
        }
        Write-Status "Java 版本低于 17 或未安装" "Red"
    } catch {
        Write-Status "Java 未安装或不在 PATH 中" "Red"
    }

    Write-Host "`n尝试自动安装 Java 17..." -ForegroundColor Yellow
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Host "winget 可用，正在安装 Microsoft.OpenJDK.17..." -ForegroundColor Yellow
        & winget install Microsoft.OpenJDK.17 --accept-source-agreements --accept-package-agreements
        if ($LASTEXITCODE -eq 0) {
            Write-Status "Java 17 安装成功，请重新运行脚本" "Green"
        } else {
            Write-Status "winget 安装失败，请手动下载：" "Red"
            Write-Host "  https://learn.microsoft.com/java/openjdk/download" -ForegroundColor Yellow
        }
    } else {
        Write-Status "winget 不可用，请手动安装 Java 17+：" "Red"
        Write-Host "  https://learn.microsoft.com/java/openjdk/download" -ForegroundColor Yellow
    }
}

function Test-Maven {
    Write-Host "`n===== [2/4] 检查 Maven 环境 =====" -ForegroundColor Cyan
    try {
        $versionOutput = & mvn --version 2>&1
        $versionString = ($versionOutput | Out-String).Trim()
        if ($versionString -match 'Apache Maven (\d+)\.(\d+)') {
            $major = [int]$Matches[1]
            $minor = [int]$Matches[2]
            Write-Host "检测到: Apache Maven $major.$minor" -ForegroundColor Gray
            $script:MavenOk = $true
            Write-Status "Maven 可用" "Green"
            return
        }
    } catch {
        Write-Status "Maven 未安装或不在 PATH 中" "Red"
    }

    Write-Host "`n尝试自动安装 Maven..." -ForegroundColor Yellow
    if (Get-Command winget -ErrorAction SilentlyContinue) {
        Write-Host "winget 可用，正在安装 Apache.Maven.3..." -ForegroundColor Yellow
        & winget install Apache.Maven.3 --accept-source-agreements --accept-package-agreements
        if ($LASTEXITCODE -eq 0) {
            Write-Status "Maven 安装成功，请重新运行脚本" "Green"
        } else {
            Write-Status "winget 安装失败，请手动下载：" "Red"
            Write-Host "  https://maven.apache.org/download.cgi" -ForegroundColor Yellow
        }
    } else {
        Write-Status "winget 不可用，请手动安装 Maven：" "Red"
        Write-Host "  https://maven.apache.org/download.cgi" -ForegroundColor Yellow
    }
}

function Test-Jar {
    Write-Host "`n===== [3/4] 检查 JAR 包 =====" -ForegroundColor Cyan
    $jarPath = Join-Path $script:ProjectRoot "target\postgres-router.jar"
    if (Test-Path $jarPath) {
        $size = (Get-Item $jarPath).Length / 1MB
        Write-Host "JAR 路径: $jarPath" -ForegroundColor Gray
        Write-Host "JAR 大小: $([math]::Round($size, 2)) MB" -ForegroundColor Gray
        $script:JarOk = $true
        Write-Status "JAR 包已存在" "Green"
        return
    }

    Write-Status "JAR 包不存在: $jarPath" "Yellow"
    if (-not $script:MavenOk) {
        Write-Status "Maven 不可用，无法构建 JAR" "Red"
        return
    }

    Write-Host "`n尝试自动构建 JAR (mvn package -DskipTests)..." -ForegroundColor Yellow
    Push-Location $script:ProjectRoot
    try {
        & mvn package -DskipTests -q 2>&1
        if ($LASTEXITCODE -eq 0 -and (Test-Path $jarPath)) {
            $size = (Get-Item $jarPath).Length / 1MB
            Write-Status "JAR 构建成功！大小: $([math]::Round($size, 2)) MB" "Green"
            $script:JarOk = $true
        } else {
            Write-Status "JAR 构建失败，请手动运行: mvn package -DskipTests" "Red"
        }
    } finally {
        Pop-Location
    }
}

function Test-Config {
    Write-Host "`n===== [4/4] 检查 config.yml =====" -ForegroundColor Cyan
    $configPath = Join-Path $script:ProjectRoot "config.yml"
    if (Test-Path $configPath) {
        Write-Host "配置文件: $configPath" -ForegroundColor Gray
        $script:ConfigOk = $true
        Write-Status "config.yml 已存在" "Green"
        return
    }

    Write-Status "config.yml 不存在" "Yellow"
    $examplePath = Join-Path $script:ProjectRoot "config.yml.example"
    if (Test-Path $examplePath) {
        Copy-Item $examplePath $configPath
        Write-Status "已从 config.yml.example 复制创建 config.yml" "Green"
        $script:ConfigOk = $true
    } else {
        Write-Status "未找到 config.yml.example，请手动创建 config.yml" "Yellow"
        Write-Host "参考文档或联系管理员获取配置模板。" -ForegroundColor Yellow
    }
}

function Show-Summary {
    Write-Host "`n===== 检查总结 =====" -ForegroundColor Cyan
    $checks = @(
        @{ Name = "Java >= 17"; Ok = $script:JavaOk },
        @{ Name = "Maven"; Ok = $script:MavenOk },
        @{ Name = "JAR 包"; Ok = $script:JarOk },
        @{ Name = "config.yml"; Ok = $script:ConfigOk }
    )

    foreach ($check in $checks) {
        $status = if ($check.Ok) { "✅ 通过" } else { "❌ 失败" }
        $color = if ($check.Ok) { "Green" } else { "Red" }
        Write-Host "  $($check.Name): " -NoNewline
        Write-Host $status -ForegroundColor $color
    }

    $allOk = $checks | ForEach-Object { $_.Ok } | Where-Object { -$_ } | Measure-Object
    if ($allOk.Count -eq 0) {
        Write-Host "`n🎉 全部检查通过，环境就绪！" -ForegroundColor Green
        return $true
    } else {
        Write-Host "`n⚠️ 部分检查失败，请根据上方提示修复后重新运行脚本。" -ForegroundColor Yellow
        return $false
    }
}

# ========== 主入口 ==========
Clear-Host
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  postgres-router 环境检查脚本" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Test-Java
Test-Maven
Test-Jar
Test-Config

$ready = Show-Summary

if ($ready) {
    Write-Host "`n正在启动 postgres-router..." -ForegroundColor Green
    $jarPath = Join-Path $script:ProjectRoot "target\postgres-router.jar"
    Start-Process -FilePath "java" -ArgumentList "-jar", $jarPath
    Write-Host "已启动！浏览器访问 http://127.0.0.1:18880" -ForegroundColor Green
}

Write-Host "`n按任意键退出..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
