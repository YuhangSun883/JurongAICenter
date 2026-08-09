<#
.SYNOPSIS
    图生视频端到端测试脚本（通过 Spring Boot /api/video/image-to-video）
.DESCRIPTION
    需要 Spring Boot 已在 http://localhost:8080 运行（Java 21 环境）。
    流程：
      1. 生成测试图片
      2. 注册测试用户 + 登录拿 accessToken
      3. POST /api/video/image-to-video 提交图生视频
      4. 轮询 GET /api/jobs/{id} 直到 COMPLETED
      5. 下载视频验证

.NOTES
    需求：PowerShell 5+，Spring Boot 运行中
#>

#Requires -Version 5.0

# ============================================================
# 配置
# ============================================================
$ServerUrl = "http://localhost:8080"   # Spring Boot 地址

# 测试用户（如果已存在直接登录，不存在先注册）
$TestEmail = "i2v-test@jurong.com"
$TestPassword = "test12345678"

# 测试参数
$Prompt = "镜头缓慢推进，画面中的渐变色彩流动变化，梦幻氛围"
$Duration = 4
$Resolution = "480P"

# 工作目录
$WorkDir = "$PSScriptRoot\..\artifacts\i2v-test"
if (!(Test-Path $WorkDir)) { New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null }
$ImagePath = Join-Path $WorkDir "test-input.png"

# 工具函数
function Write-Step($n, $msg) { Write-Host "`n========== 步骤 $n: $msg ==========" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "[OK] $msg" -ForegroundColor Green }
function Write-Fail($msg) { Write-Host "[FAIL] $msg" -ForegroundColor Red }
function Write-Info($msg) { Write-Host "[INFO] $msg" -ForegroundColor Yellow }

# ============================================================
# 步骤 1: 生成测试图片
# ============================================================
Write-Step 1 "生成测试图片"

try {
    Add-Type -AssemblyName System.Drawing
    $bmp = New-Object System.Drawing.Bitmap(512, 512)
    $gfx = [System.Drawing.Graphics]::FromImage($bmp)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.Point(0, 0)),
        (New-Object System.Drawing.Point(512, 512)),
        [System.Drawing.Color]::FromArgb(255, 100, 150, 255),
        [System.Drawing.Color]::FromArgb(255, 255, 200, 100)
    )
    $gfx.FillRectangle($brush, 0, 0, 512, 512)
    $redBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(200, 255, 80, 80))
    $gfx.FillEllipse($redBrush, 150, 150, 80, 80)
    $blueBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(200, 80, 200, 255))
    $gfx.FillEllipse($blueBrush, 300, 300, 100, 100)
    $gfx.Dispose()
    $bmp.Save($ImagePath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $imgSize = (Get-Item $ImagePath).Length
    Write-Ok "图片已生成: $ImagePath ($imgSize bytes)"
} catch {
    Write-Fail "生成图片失败: $_"
    exit 1
}

# ============================================================
# 步骤 2: 注册 + 登录拿 accessToken
# ============================================================
Write-Step 2 "注册测试用户 + 登录"

# 先尝试注册（已存在会报错，忽略）
try {
    $regBody = @{ email = $TestEmail; password = $TestPassword; displayName = "I2V Tester" } | ConvertTo-Json
    Invoke-RestMethod -Uri "$ServerUrl/api/auth/register" -Method Post -Body $regBody -ContentType "application/json" -TimeoutSec 10 | Out-Null
    Write-Ok "注册成功"
} catch {
    Write-Info "注册失败（用户可能已存在），继续登录"
}

# 登录
try {
    $loginBody = @{ email = $TestEmail; password = $TestPassword } | ConvertTo-Json
    $loginResp = Invoke-RestMethod -Uri "$ServerUrl/api/auth/login" -Method Post -Body $loginBody -ContentType "application/json" -TimeoutSec 10
    $accessToken = $loginResp.accessToken
    $userId = $loginResp.userId
    Write-Ok "登录成功: userId=$userId, tokenLen=$($accessToken.Length)"
} catch {
    Write-Fail "登录失败: $_"
    exit 1
}

# ============================================================
# 步骤 3: 提交图生视频 (POST /api/video/image-to-video)
# ============================================================
Write-Step 3 "提交图生视频请求"

try {
    # 用 .NET HttpClient 构造 multipart（含 file + prompt + duration + resolution）
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(300)  # asset 上传+轮询需要时间
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer $accessToken")

    $content = New-Object System.Net.Http.MultipartFormDataContent

    # file 字段（图片）
    $fileBytes = [System.IO.File]::ReadAllBytes($ImagePath)
    $fileContent = New-Object System.Net.Http.ByteArrayContent($fileBytes)
    $fileContent.Headers.Add("Content-Type", "image/png")
    $content.Add($fileContent, "file", "test-input.png")

    # prompt 字段
    $promptContent = New-Object System.Net.Http.StringContent($Prompt)
    $content.Add($promptContent, "prompt")

    # duration 字段
    $durationContent = New-Object System.Net.Http.StringContent($Duration)
    $content.Add($durationContent, "duration")

    # resolution 字段
    $resolutionContent = New-Object System.Net.Http.StringContent($Resolution)
    $content.Add($resolutionContent, "resolution")

    Write-Info "POST $ServerUrl/api/video/image-to-video (prompt='$Prompt', size=$($fileBytes.Length)B)"
    $response = $client.PostAsync("$ServerUrl/api/video/image-to-video", $content).Result
    $respBody = $response.Content.ReadAsStringAsync().Result

    if (!$response.IsSuccessStatusCode) {
        Write-Fail "提交失败: HTTP $($response.StatusCode)"
        Write-Host "响应: $respBody"
        exit 1
    }

    $respJson = $respBody | ConvertFrom-Json
    $jobId = $respJson.jobId
    $jobStatus = $respJson.status
    $taskId = $respJson.comfyuiPromptId

    Write-Ok "任务已提交: jobId=$jobId, status=$jobStatus, taskId=$taskId"
} catch {
    Write-Fail "提交异常: $_"
    exit 1
}

# ============================================================
# 步骤 4: 轮询 job 状态 (GET /api/jobs/{id})
# ============================================================
Write-Step 4 "轮询任务状态 (最多 30 分钟)"

$maxWait = 1800
$pollInterval = 10
$elapsed = 0
$jobDone = $false
$videoUrl = $null

while ($elapsed -lt $maxWait) {
    Start-Sleep -Seconds $pollInterval
    $elapsed += $pollInterval

    try {
        $jobResp = Invoke-RestMethod -Uri "$ServerUrl/api/jobs/$jobId" `
            -Method Get `
            -Headers @{ Authorization = "Bearer $accessToken" } `
            -TimeoutSec 30

        $status = $jobResp.status
        Write-Info "job $jobId 状态: $status (elapsed=${elapsed}s)"

        if ($status -eq "COMPLETED") {
            Write-Ok "任务完成! (耗时 ${elapsed}s)"
            # resultUrls 是 JSON 数组字符串
            $resultUrls = $jobResp.resultUrls
            if ($resultUrls) {
                $urls = $resultUrls | ConvertFrom-Json
                $videoUrl = $urls[0]
                Write-Info "视频 URL: $videoUrl"
            }
            Write-Info "完整 job: $($jobResp | ConvertTo-Json -Depth 5)"
            $jobDone = $true
            break
        }

        if ($status -eq "FAILED") {
            Write-Fail "任务失败: $($jobResp.errorMessage)"
            Write-Info "完整 job: $($jobResp | ConvertTo-Json -Depth 5)"
            break
        }
    } catch {
        Write-Info "轮询出错 (重试): $_"
    }
}

if (!$jobDone) {
    Write-Fail "任务未完成"
    exit 1
}

# ============================================================
# 步骤 5: 下载视频
# ============================================================
if ($videoUrl) {
    Write-Step 5 "下载视频"

    $videoPath = Join-Path $WorkDir "test-output.mp4"
    try {
        # 如果是 MinIO 签名 URL，直接下载
        Invoke-WebRequest -Uri $videoUrl -OutFile $videoPath -TimeoutSec 300
        $videoSize = (Get-Item $videoPath).Length
        Write-Ok "视频已下载: $videoPath ($videoSize bytes)"
    } catch {
        Write-Info "直接下载失败，尝试通过 Spring Boot 代理下载..."
        # 通过 Spring Boot 的 result 端点下载
        $filename = Split-Path $videoUrl -Leaf
        try {
            Invoke-WebRequest -Uri "$ServerUrl/api/jobs/$jobId/result/$filename" `
                -Headers @{ Authorization = "Bearer $accessToken" } `
                -OutFile $videoPath -TimeoutSec 300
            $videoSize = (Get-Item $videoPath).Length
            Write-Ok "视频已下载(代理): $videoPath ($videoSize bytes)"
        } catch {
            Write-Fail "下载失败: $_"
        }
    }
}

Write-Host "`n========== 测试完成 ==========" -ForegroundColor Green
Write-Host "测试产物目录: $WorkDir" -ForegroundColor Cyan
Write-Host ""
Write-Host "查看 Spring Boot 日志（重点关注以下前缀）：" -ForegroundColor Yellow
Write-Host "  [I2V-REQ]      - HTTP 请求入口" -ForegroundColor Yellow
Write-Host "  [I2V-SUBMIT]   - 同步提交链路（asset 上传 + 视频提交）" -ForegroundColor Yellow
Write-Host "  [ASSET-UP]     - asset 上传请求/响应" -ForegroundColor Yellow
Write-Host "  [ASSET-POLL]   - asset 轮询状态" -ForegroundColor Yellow
Write-Host "  [VIDEO-SUBMIT] - NewAPI 视频提交（含完整请求体+响应）" -ForegroundColor Yellow
Write-Host "  [I2V-POLL]     - 异步轮询（含 NewAPI 原始响应）" -ForegroundColor Yellow
Write-Host "  [I2V-DONE]     - 完成/下载/MinIO" -ForegroundColor Yellow
