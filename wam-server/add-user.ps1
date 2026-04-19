# -------------------------------------------------------------------------------
# WA Mirror - Add User Script
# Usage:  .\add-user.ps1
#         .\add-user.ps1 -Username yaniv -Token mysecrettoken
#         .\add-user.ps1 -List
#         .\add-user.ps1 -Delete -Username yaniv
#         .\add-user.ps1 -Help
# -------------------------------------------------------------------------------
param (
    [string] $Username   = "",
    [string] $Token      = "",
    [string] $ServerUrl  = "",
    [string] $AdminToken = "",
    [switch] $List,
    [switch] $Delete,
    [switch] $Help
)

$ConfigFile = Join-Path $PSScriptRoot "add-user.config.json"

function Load-Config {
    if (Test-Path $ConfigFile) {
        return Get-Content $ConfigFile -Raw | ConvertFrom-Json
    }
    return $null
}

function Save-Config([string]$url, [string]$token) {
    @{ ServerUrl = $url; AdminToken = $token } | ConvertTo-Json | Set-Content $ConfigFile -Encoding UTF8
    Write-Host "  Config saved to $ConfigFile" -ForegroundColor DarkGray
}

# ---- Banner ------------------------------------------------------------------
Write-Host ""
Write-Host "  ================================================" -ForegroundColor Cyan
Write-Host "       WA Mirror - User Management               " -ForegroundColor Cyan
Write-Host "  ================================================" -ForegroundColor Cyan
Write-Host ""

if ($Help) {
    Write-Host "  Usage examples:" -ForegroundColor Yellow
    Write-Host "    .\add-user.ps1                           # interactive mode"
    Write-Host "    .\add-user.ps1 -Username bob -Token s3cr3t"
    Write-Host "    .\add-user.ps1 -List                     # list all users"
    Write-Host "    .\add-user.ps1 -Delete -Username bob     # delete a user"
    Write-Host ""
    exit 0
}

# ---- Load or prompt for server config ----------------------------------------
$cfg = Load-Config

if ($ServerUrl -eq "") {
    if ($cfg -and $cfg.ServerUrl) {
        $defaultUrl = $cfg.ServerUrl
        $inputUrl = Read-Host "  Server URL [$defaultUrl]"
        $ServerUrl = if ($inputUrl -eq "") { $defaultUrl } else { $inputUrl }
    } else {
        $ServerUrl = Read-Host "  Server URL (e.g. https://192.168.1.100:3000)"
    }
}
$ServerUrl = $ServerUrl.TrimEnd("/")

if ($AdminToken -eq "") {
    if ($cfg -and $cfg.AdminToken) {
        $defaultToken = $cfg.AdminToken
        $maskedLen = [Math]::Min($defaultToken.Length, 6)
        $masked = ("*" * $maskedLen) + "..."
        $inputToken = Read-Host "  Admin token [$masked] (press Enter to reuse)"
        $AdminToken = if ($inputToken -eq "") { $defaultToken } else { $inputToken }
    } else {
        $AdminToken = Read-Host "  Admin token (ADMIN_TOKEN from .env)"
    }
}

Save-Config $ServerUrl $AdminToken

$Headers = @{
    "X-Admin-Token" = $AdminToken
    "Content-Type"  = "application/json"
}

# ---- API helper --------------------------------------------------------------
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Body = $null
    )
    $uri = "$ServerUrl$Path"
    try {
        if ($Body) {
            $response = Invoke-RestMethod -Method $Method -Uri $uri -Headers $Headers `
                -Body ($Body | ConvertTo-Json) -ErrorAction Stop
        } else {
            $response = Invoke-RestMethod -Method $Method -Uri $uri -Headers $Headers `
                -ErrorAction Stop
        }
        return $response
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        $detail = $_.ErrorDetails.Message
        Write-Host "  [ERROR] HTTP $status - $detail" -ForegroundColor Red
        return $null
    }
}

# ---- LIST --------------------------------------------------------------------
if ($List) {
    Write-Host "  Fetching users from $ServerUrl ..." -ForegroundColor DarkGray
    $users = Invoke-Api -Method "GET" -Path "/admin/users"
    if ($null -eq $users) { exit 1 }

    if ($users.Count -eq 0) {
        Write-Host "  No users found." -ForegroundColor Yellow
    } else {
        Write-Host ""
        Write-Host ("  {0,-36}  {1,-20}  {2}" -f "ID", "Username", "Created") -ForegroundColor Cyan
        Write-Host ("  " + ("-" * 70)) -ForegroundColor DarkGray
        foreach ($u in $users) {
            $created = [DateTimeOffset]::FromUnixTimeSeconds($u.created_at).LocalDateTime.ToString("yyyy-MM-dd HH:mm")
            Write-Host ("  {0,-36}  {1,-20}  {2}" -f $u.id, $u.username, $created)
        }
    }
    Write-Host ""
    exit 0
}

# ---- DELETE ------------------------------------------------------------------
if ($Delete) {
    if ($Username -eq "") {
        $Username = Read-Host "  Username to delete"
    }

    $users = Invoke-Api -Method "GET" -Path "/admin/users"
    if ($null -eq $users) { exit 1 }

    $user = $users | Where-Object { $_.username -eq $Username }
    if ($null -eq $user) {
        Write-Host "  [ERROR] User '$Username' not found." -ForegroundColor Red
        exit 1
    }

    $confirm = Read-Host "  Delete user '$Username' and ALL their data? (yes/no)"
    if ($confirm -ne "yes") {
        Write-Host "  Cancelled." -ForegroundColor Yellow
        exit 0
    }

    $result = Invoke-Api -Method "DELETE" -Path "/admin/users/$($user.id)"
    if ($result) {
        Write-Host "  [OK] User '$Username' deleted." -ForegroundColor Green
    }
    Write-Host ""
    exit 0
}

# ---- ADD USER ----------------------------------------------------------------
Write-Host "  -- Add New User --" -ForegroundColor Yellow
Write-Host ""

if ($Username -eq "") {
    $Username = Read-Host "  Username"
}
if ($Username -eq "") {
    Write-Host "  [ERROR] Username cannot be empty." -ForegroundColor Red
    exit 1
}

if ($Token -eq "") {
    $inputToken = Read-Host "  Token (press Enter to auto-generate)"
    if ($inputToken -eq "") {
        $chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        $Token = -join ((1..32) | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
        Write-Host "  Generated token: $Token" -ForegroundColor DarkCyan
    } else {
        $Token = $inputToken
    }
}

Write-Host ""
Write-Host "  Creating user ..." -ForegroundColor DarkGray

$body   = @{ username = $Username; token = $Token }
$result = Invoke-Api -Method "POST" -Path "/admin/users" -Body $body

if ($result) {
    Write-Host ""
    Write-Host "  [OK] User created successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host ("  {0,-15} {1}" -f "Username:",  $Username)  -ForegroundColor White
    Write-Host ("  {0,-15} {1}" -f "User ID:",   $result.id) -ForegroundColor White
    Write-Host ("  {0,-15} {1}" -f "Token:",     $Token)     -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  --> Enter this token in the Android app Settings screen." -ForegroundColor Cyan
    Write-Host ""

    $clip = Read-Host "  Copy token to clipboard? (y/n)"
    if ($clip -eq "y") {
        Set-Clipboard -Value $Token
        Write-Host "  [OK] Token copied to clipboard." -ForegroundColor Green
    }
}

Write-Host ""
