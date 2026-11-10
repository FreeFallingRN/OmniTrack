<#
.SYNOPSIS
    Verifies Supabase PostgREST connectivity and table structure for OmniTrack.

.DESCRIPTION
    Loads SUPABASE_URL and SUPABASE_ANON_KEY from the project root .env file
    and performs read queries against the PostgREST API to verify migration status.

.EXAMPLE
    .\verify_connection.ps1
#>

$ErrorActionPreference = "Stop"

$envPath = Join-Path $PSScriptRoot "..\.env"
if (-not (Test-Path $envPath)) {
    Write-Host "[ERROR] .env file not found at $envPath." -ForegroundColor Red
    Write-Host "Please copy .env.example to .env and fill in your Supabase credentials." -ForegroundColor Yellow
    exit 1
}

# Parse .env file
$envContent = Get-Content $envPath
$supabaseUrl = ""
$supabaseAnonKey = ""

foreach ($line in $envContent) {
    $trimmed = $line.Trim()
    if ($trimmed -and -not $trimmed.StartsWith("#")) {
        $parts = $trimmed -split "=", 2
        if ($parts.Length -eq 2) {
            $key = $parts[0].Trim()
            $val = $parts[1].Trim().Trim('"').Trim("'")
            if ($key -eq "SUPABASE_URL") { $supabaseUrl = $val }
            if ($key -eq "SUPABASE_ANON_KEY") { $supabaseAnonKey = $val }
        }
    }
}

if (-not $supabaseUrl -or $supabaseUrl -like "*your-project-id*") {
    Write-Host "[ERROR] SUPABASE_URL is not configured in .env." -ForegroundColor Red
    exit 1
}

if (-not $supabaseAnonKey -or $supabaseAnonKey.StartsWith("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")) {
    Write-Host "[ERROR] SUPABASE_ANON_KEY is not configured in .env." -ForegroundColor Red
    exit 1
}

Write-Host "=== OmniTrack Supabase Verification ===" -ForegroundColor Cyan
Write-Host "Target URL: $supabaseUrl"

$headers = @{
    "apikey"        = $supabaseAnonKey
    "Authorization" = "Bearer $supabaseAnonKey"
    "Content-Type"  = "application/json"
}

# 1. Query series
Write-Host "`n1. Testing GET /rest/v1/series..." -NoNewline
try {
    $seriesUri = "$($supabaseUrl.TrimEnd('/'))/rest/v1/series?select=id,title,type,user_status,publishing_status,current_cumulative_unit,is_active_target&limit=5"
    $response = Invoke-RestMethod -Uri $seriesUri -Headers $headers -Method Get
    Write-Host " [SUCCESS]" -ForegroundColor Green
    Write-Host "   Retrieved $($response.Count) series."
    foreach ($item in $response) {
        $activeBadge = if ($item.is_active_target) { " (ACTIVE TARGET)" } else { "" }
        Write-Host "   - [$($item.type)] $($item.title) ($($item.user_status)) - Ch. $($item.current_cumulative_unit)$activeBadge"
    }
} catch {
    Write-Host " [FAILED]" -ForegroundColor Red
    Write-Host "   Error: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails) {
        Write-Host "   Details: $($_.ErrorDetails.Message)" -ForegroundColor Red
    }
    exit 1
}

# 2. Query media_entries
Write-Host "`n2. Testing GET /rest/v1/media_entries..." -NoNewline
try {
    $entriesUri = "$($supabaseUrl.TrimEnd('/'))/rest/v1/media_entries?select=id,series_id,title,sequence_number,current_unit,is_active_target&limit=5"
    $responseEntries = Invoke-RestMethod -Uri $entriesUri -Headers $headers -Method Get
    Write-Host " [SUCCESS]" -ForegroundColor Green
    Write-Host "   Retrieved $($responseEntries.Count) media entry record(s)."
} catch {
    Write-Host " [FAILED]" -ForegroundColor Red
    Write-Host "   Error: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails) {
        Write-Host "   Details: $($_.ErrorDetails.Message)" -ForegroundColor Red
    }
    exit 1
}

# 3. Query progress_history
Write-Host "`n3. Testing GET /rest/v1/progress_history..." -NoNewline
try {
    $historyUri = "$($supabaseUrl.TrimEnd('/'))/rest/v1/progress_history?select=id,series_id,entry_id,previous_unit,new_unit,source,logged_at&limit=5"
    $responseHistory = Invoke-RestMethod -Uri $historyUri -Headers $headers -Method Get
    Write-Host " [SUCCESS]" -ForegroundColor Green
    Write-Host "   Retrieved $($responseHistory.Count) history record(s)."
} catch {
    Write-Host " [FAILED]" -ForegroundColor Red
    Write-Host "   Error: $($_.Exception.Message)" -ForegroundColor Red
    if ($_.ErrorDetails) {
        Write-Host "   Details: $($_.ErrorDetails.Message)" -ForegroundColor Red
    }
    exit 1
}

Write-Host "`n[SUCCESS] Supabase database migrations and PostgREST endpoints are functional!" -ForegroundColor Green
