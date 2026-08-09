<#
.SYNOPSIS
    Image-to-Video direct test script (bypass Spring Boot, test remote proxy + NewAPI directly)
.DESCRIPTION
    Validates Assets-API manual section 5 end-to-end flow:
      1. Generate test image
      2. POST proxy:8080/v1/assets (upload image)
      3. GET proxy:8080/v1/assets/{id} (poll until active)
      4. POST newapi:3000/v1/videos (submit video task with image_urls)
      5. GET newapi:3000/v1/videos/{taskId} (poll until completed)
      6. Download video to verify
      7. DELETE proxy:8080/v1/assets/{id} (cleanup)

    If image_urls field name is wrong, step 4 will show 4xx error.
#>

#Requires -Version 5.0

# ============================================================
# Config (from application-dev.yml)
# ============================================================
$ProxyBaseUrl  = "http://192.140.163.161:8080"   # aicoming-video-proxy (asset CRUD)
$NewApiBaseUrl = "http://192.140.163.161:3000"   # NewAPI relay (video generation)
$Token         = "sk-wPT3JECu4V4gX9qcH4xTmLyc3sUYvntHgZXUnUUeU3AlohPF"

# Test params
$Prompt     = "Camera slowly pushes in, gradient colors flow and change, dreamy atmosphere"
$Duration   = "4"        # string (manual requires string, not int)
$Resolution = "480p"     # lowercase! aicoming rejects "480P"
$Model      = "doubao-seedance-2.0"

# Work dir
$WorkDir  = Join-Path $PSScriptRoot "..\artifacts\i2v-test"
$WorkDir  = [System.IO.Path]::GetFullPath($WorkDir)
if (!(Test-Path $WorkDir)) { New-Item -ItemType Directory -Path $WorkDir -Force | Out-Null }
$ImagePath = Join-Path $WorkDir "test-input.png"

# ============================================================
# Helpers (use ${var} to avoid $n: drive-colon issue in PS5)
# ============================================================
function Write-Step($num, $msg) {
    Write-Host "`n========== Step ${num}: ${msg} ==========" -ForegroundColor Cyan
}
function Write-Ok($msg)    { Write-Host "[OK]   ${msg}" -ForegroundColor Green }
function Write-Fail($msg)  { Write-Host "[FAIL] ${msg}" -ForegroundColor Red }
function Write-Info($msg)  { Write-Host "[INFO] ${msg}" -ForegroundColor Yellow }

# ============================================================
# Step 1: Generate test image (512x512 gradient PNG)
# ============================================================
Write-Step 1 "Generate test image"

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
    Write-Ok "Image generated: ${ImagePath} (${imgSize} bytes)"
} catch {
    Write-Fail "Generate image failed: $_"
    exit 1
}

# ============================================================
# Step 2: Upload image to proxy 8080 /v1/assets (multipart)
# ============================================================
Write-Step 2 "Upload image to aicoming-proxy /v1/assets"

$assetId  = $null
$assetUrl = $null

try {
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $client.DefaultRequestHeaders.Add("Authorization", "Bearer ${Token}")

    $content = New-Object System.Net.Http.MultipartFormDataContent
    # Use StreamContent (not ByteArrayContent) to avoid PS5 expanding byte[] into ctor args
    $fileStream = [System.IO.File]::OpenRead($ImagePath)
    $fileContent = New-Object System.Net.Http.StreamContent($fileStream)
    $fileContent.Headers.Add("Content-Type", "image/png")
    $content.Add($fileContent, "file", "test-input.png")
    $nameContent = New-Object System.Net.Http.StringContent("i2v-test")
    $content.Add($nameContent, "name")

    $fileSize = $fileStream.Length
    Write-Info "POST ${ProxyBaseUrl}/v1/assets (multipart, size=${fileSize}B)"
    $response = $client.PostAsync("${ProxyBaseUrl}/v1/assets", $content).Result
    $respBody = $response.Content.ReadAsStringAsync().Result
    $fileStream.Dispose()

    if (!$response.IsSuccessStatusCode) {
        Write-Fail "Upload failed: HTTP $($response.StatusCode)"
        Write-Host "Response: ${respBody}"
        exit 1
    }

    Write-Info "Response: ${respBody}"
    $respJson = $respBody | ConvertFrom-Json

    if ($respJson.code -ne 0) {
        Write-Fail "Upload failed: code=$($respJson.code), message=$($respJson.message)"
        exit 1
    }

    $assetId    = $respJson.data.id
    $assetUrl   = $respJson.data.asset_url
    $assetStatus = $respJson.data.status
    Write-Ok "Upload OK: id=${assetId}, asset_url=${assetUrl}, status=${assetStatus}"

    if (!$assetId -or !$assetUrl) {
        Write-Fail "Response missing id or asset_url"
        exit 1
    }
} catch {
    Write-Fail "Upload exception: $_"
    exit 1
}

# ============================================================
# Step 3: Poll asset until active (GET /v1/assets/{id})
# ============================================================
Write-Step 3 "Poll asset until active (max 90s)"

$maxWait  = 90
$interval = 3
$elapsed  = 0
$assetActive = $false

while ($elapsed -lt $maxWait) {
    Start-Sleep -Seconds $interval
    $elapsed += $interval

    try {
        $pollResp = Invoke-RestMethod -Uri "${ProxyBaseUrl}/v1/assets/${assetId}" `
            -Method Get `
            -Headers @{ Authorization = "Bearer ${Token}" } `
            -TimeoutSec 30

        $status = $pollResp.data.status
        $pollCount = [math]::Floor($elapsed / $interval)
        Write-Info "Poll #${pollCount}: status=${status} (elapsed=${elapsed}s)"

        if ($status -eq "active") {
            Write-Ok "Asset is active! (took ${elapsed}s)"
            $assetActive = $true
            break
        }
        if ($status -eq "failed") {
            Write-Fail "Asset processing failed: $($pollResp.data | ConvertTo-Json -Depth 5)"
            exit 1
        }
    } catch {
        Write-Info "Poll error (retry): $_"
    }
}

if (!$assetActive) {
    Write-Fail "Asset not active within ${maxWait}s"
    exit 1
}

# ============================================================
# Step 4: Submit video generation (POST newapi:3000/v1/videos, JSON body)
# ============================================================
Write-Step 4 "Submit video to NewAPI /v1/videos (image_urls ref asset)"

$videoBody = @{
    model      = $Model
    prompt     = $Prompt
    image_urls = @($assetUrl)    # array, referencing asset_url
    duration   = $Duration       # string, not int
    resolution = $Resolution
} | ConvertTo-Json -Depth 5

Write-Info "POST ${NewApiBaseUrl}/v1/videos"
Write-Info "Request body: ${videoBody}"

$taskId = $null

try {
    $videoResp = Invoke-RestMethod -Uri "${NewApiBaseUrl}/v1/videos" `
        -Method Post `
        -Headers @{ Authorization = "Bearer ${Token}"; "Content-Type" = "application/json" } `
        -Body $videoBody `
        -TimeoutSec 600

    Write-Info "Response: $($videoResp | ConvertTo-Json -Depth 5)"

    $taskId = $videoResp.id
    if (!$taskId) { $taskId = $videoResp.task_id }

    if (!$taskId) {
        Write-Fail "No task_id / id in response"
        Write-Host "Full response: $($videoResp | ConvertTo-Json -Depth 5)"
        Write-Host ""
        Write-Host "If error mentions image_urls / asset_url field name:" -ForegroundColor Yellow
        Write-Host "  Change image_urls to asset_url (single value, not array) in script" -ForegroundColor Yellow
        exit 1
    }

    Write-Ok "Video task submitted: taskId=${taskId}"
} catch {
    Write-Fail "Submit video failed: $_"
    $resp = $_.Exception.Response
    if ($resp) {
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $errBody = $reader.ReadToEnd()
        Write-Host "HTTP $($resp.StatusCode.value__): ${errBody}" -ForegroundColor Red
    }
    Write-Host ""
    Write-Host "Troubleshooting:" -ForegroundColor Yellow
    Write-Host "  - 4xx + image_urls invalid -> use asset_url field (single value)" -ForegroundColor Yellow
    Write-Host "  - 401 -> token expired or invalid" -ForegroundColor Yellow
    Write-Host "  - 404 -> /v1/videos path wrong, check NewAPI version" -ForegroundColor Yellow
    exit 1
}

# ============================================================
# Step 5: Poll video task until completed (GET /v1/videos/{taskId})
# ============================================================
Write-Step 5 "Poll video task until completed (max 30min)"

$maxVideoWait  = 1800   # 30 min
$videoInterval = 10
$videoElapsed  = 0
$videoDone     = $false
$videoUrl      = $null

while ($videoElapsed -lt $maxVideoWait) {
    Start-Sleep -Seconds $videoInterval
    $videoElapsed += $videoInterval

    try {
        $statusResp = Invoke-RestMethod -Uri "${NewApiBaseUrl}/v1/videos/${taskId}" `
            -Method Get `
            -Headers @{ Authorization = "Bearer ${Token}" } `
            -TimeoutSec 30

        $vStatus = $statusResp.status
        Write-Info "Poll video: status=${vStatus} (elapsed=${videoElapsed}s)"

        if ($vStatus -in @("completed", "succeeded", "success")) {
            $videoUrl = $statusResp.metadata.url
            if (!$videoUrl -and $statusResp.result) {
                $videoUrl = $statusResp.result.metadata.url
                if (!$videoUrl) { $videoUrl = $statusResp.result.url }
            }
            if (!$videoUrl) { $videoUrl = $statusResp.url }

            Write-Ok "Video completed! (took ${videoElapsed}s)"
            Write-Info "Video URL: ${videoUrl}"
            Write-Info "Full response: $($statusResp | ConvertTo-Json -Depth 5)"
            $videoDone = $true
            break
        }

        if ($vStatus -in @("failed", "error", "cancelled")) {
            Write-Fail "Video task failed: $($statusResp | ConvertTo-Json -Depth 5)"
            break
        }
    } catch {
        Write-Info "Poll error (retry): $_"
    }
}

if (!$videoDone) {
    Write-Fail "Video task not completed (timeout or failed)"
    exit 1
}

# ============================================================
# Step 6: Download video to verify
# ============================================================
if ($videoUrl) {
    Write-Step 6 "Download video to verify"

    $videoPath = Join-Path $WorkDir "test-output.mp4"
    try {
        Invoke-WebRequest -Uri $videoUrl -OutFile $videoPath -TimeoutSec 300
        $videoSize = (Get-Item $videoPath).Length
        Write-Ok "Video downloaded: ${videoPath} (${videoSize} bytes)"
    } catch {
        Write-Fail "Download video failed: $_"
    }
}

# ============================================================
# Step 7: Cleanup asset (DELETE /v1/assets/{id})
# ============================================================
Write-Step 7 "Cleanup asset (best-effort)"

try {
    $delResp = Invoke-RestMethod -Uri "${ProxyBaseUrl}/v1/assets/${assetId}" `
        -Method Delete `
        -Headers @{ Authorization = "Bearer ${Token}" } `
        -TimeoutSec 30
    Write-Ok "Asset deleted: $($delResp | ConvertTo-Json -Depth 3)"
} catch {
    Write-Info "Asset delete failed (best-effort, ignored): $_"
}

Write-Host "`n========== Test Done ==========" -ForegroundColor Green
Write-Host "Test artifacts dir: ${WorkDir}" -ForegroundColor Cyan
