<#
    Phase 1 verification - drives the auth and user API over HTTP and asserts
    the security-critical behaviours, not just the happy path.

    Usage (server must already be running):
        .\scripts\verify-phase1.ps1
        .\scripts\verify-phase1.ps1 -BaseUrl http://localhost:8080

    Written for Windows PowerShell 5.1: no ternary, no ?? operators.
#>

param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$api = "$BaseUrl/api/v1"

$script:Passed = 0
$script:Failed = 0

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Body,
        [string]$AccessToken,
        [hashtable]$ExtraHeaders
    )

    $headers = @{
        "X-Device-Id"       = [guid]::NewGuid().ToString()
        "X-Device-Platform" = "DESKTOP"
    }
    if ($AccessToken) { $headers["Authorization"] = "Bearer $AccessToken" }
    if ($ExtraHeaders) { foreach ($k in $ExtraHeaders.Keys) { $headers[$k] = $ExtraHeaders[$k] } }

    $params = @{
        Uri             = "$api$Path"
        Method          = $Method
        Headers         = $headers
        ContentType     = "application/json"
        UseBasicParsing = $true
    }
    if ($Body) { $params["Body"] = ($Body | ConvertTo-Json -Depth 5 -Compress) }

    try {
        $response = Invoke-WebRequest @params
        $parsed = $null
        if ($response.Content) {
            $parsed = $response.Content | ConvertFrom-Json -ErrorAction SilentlyContinue
        }
        return @{ Status = [int]$response.StatusCode; Body = $parsed }
    }
    catch {
        $webResponse = $_.Exception.Response
        if ($webResponse -eq $null) {
            return @{ Status = 0; Body = $null; Error = $_.Exception.Message }
        }

        # Windows PowerShell 5.1 has already drained the response stream by the
        # time we get here and parks the body in ErrorDetails.Message. Reading
        # GetResponseStream() directly returns an empty string, which silently
        # turns every error assertion into a false negative.
        $text = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $text = $_.ErrorDetails.Message
        }
        else {
            $stream = $webResponse.GetResponseStream()
            if ($stream) {
                $reader = New-Object System.IO.StreamReader($stream)
                $text = $reader.ReadToEnd()
            }
        }

        $parsed = $null
        if ($text) { $parsed = $text | ConvertFrom-Json -ErrorAction SilentlyContinue }
        return @{ Status = [int]$webResponse.StatusCode; Body = $parsed; Raw = $text }
    }
}

function Assert-That {
    param([string]$Name, [bool]$Condition, [string]$Detail)

    if ($Condition) {
        Write-Host "  PASS  $Name" -ForegroundColor Green
        $script:Passed++
    }
    else {
        Write-Host "  FAIL  $Name" -ForegroundColor Red
        if ($Detail) { Write-Host "        $Detail" -ForegroundColor DarkGray }
        $script:Failed++
    }
}

function Section { param([string]$Title); Write-Host "`n$Title" -ForegroundColor Cyan }

# ---------------------------------------------------------------------------

Write-Host "Verifying Phase 1 against $BaseUrl" -ForegroundColor White

$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$email = "verify+$stamp@example.com"
$password = "correct-horse-1"
$newPassword = "brand-new-pass-2"

Section "0. Server reachable"
try {
    $health = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing
    Assert-That "GET /actuator/health returns 200" ([int]$health.StatusCode -eq 200)
}
catch {
    Write-Host "  Server is not reachable at $BaseUrl. Start it with:" -ForegroundColor Red
    Write-Host "      cd server; .\gradlew :app:bootRun" -ForegroundColor Yellow
    exit 1
}

Section "1. Registration"
$register = Invoke-Api -Method POST -Path "/auth/register" -Body @{
    name = "Verify User"; email = $email; password = $password
}
Assert-That "register returns 201" ($register.Status -eq 201) "got $($register.Status)"
Assert-That "email is normalised to lowercase" ($register.Body.user.email -eq $email.ToLower())
Assert-That "an access token is issued" ([string]::IsNullOrEmpty($register.Body.tokens.accessToken) -eq $false)
Assert-That "a refresh token is issued" ([string]::IsNullOrEmpty($register.Body.tokens.refreshToken) -eq $false)
Assert-That "no password hash leaks into the response" ($null -eq $register.Body.user.passwordHash)

$sessionA = $register.Body.tokens

$duplicate = Invoke-Api -Method POST -Path "/auth/register" -Body @{
    name = "Someone Else"; email = $email.ToUpper(); password = $password
}
Assert-That "duplicate email rejected with 409" ($duplicate.Status -eq 409) "got $($duplicate.Status)"
Assert-That "duplicate error code is EMAIL_ALREADY_EXISTS" ($duplicate.Body.error.code -eq "EMAIL_ALREADY_EXISTS")

$weak = Invoke-Api -Method POST -Path "/auth/register" -Body @{
    name = "Weak"; email = "weak+$stamp@example.com"; password = "short"
}
Assert-That "weak password rejected with 400" ($weak.Status -eq 400) "got $($weak.Status)"
Assert-That "validation error carries field details" ($weak.Body.error.details.Count -gt 0)

Section "2. Authorisation"
$noAuth = Invoke-Api -Method GET -Path "/users/me"
Assert-That "GET /users/me without a token returns 401" ($noAuth.Status -eq 401) "got $($noAuth.Status)"
Assert-That "401 body uses the standard error envelope" ($null -ne $noAuth.Body.error.code)

$badToken = Invoke-Api -Method GET -Path "/users/me" -AccessToken "not.a.real.jwt"
Assert-That "a malformed token returns 401 TOKEN_INVALID" `
    ($badToken.Status -eq 401 -and $badToken.Body.error.code -eq "TOKEN_INVALID") `
    "got $($badToken.Status) / $($badToken.Body.error.code)"

$me = Invoke-Api -Method GET -Path "/users/me" -AccessToken $sessionA.accessToken
Assert-That "GET /users/me with a valid token returns 200" ($me.Status -eq 200) "got $($me.Status)"
Assert-That "it returns the right user" ($me.Body.email -eq $email.ToLower())

$patched = Invoke-Api -Method PATCH -Path "/users/me" -AccessToken $sessionA.accessToken -Body @{
    name = "Renamed User"
}
Assert-That "PATCH /users/me returns 200" ($patched.Status -eq 200) "got $($patched.Status)"
Assert-That "the name was persisted" ($patched.Body.name -eq "Renamed User")

Section "3. Login and user enumeration resistance"
$wrongPassword = Invoke-Api -Method POST -Path "/auth/login" -Body @{
    email = $email; password = "definitely-wrong-9"
}
Assert-That "wrong password returns 401" ($wrongPassword.Status -eq 401) "got $($wrongPassword.Status)"

$unknownEmail = Invoke-Api -Method POST -Path "/auth/login" -Body @{
    email = "nobody+$stamp@example.com"; password = $password
}
Assert-That "unknown email returns 401" ($unknownEmail.Status -eq 401) "got $($unknownEmail.Status)"

# The point of the dummy-hash branch: these two must be indistinguishable.
Assert-That "unknown email and wrong password share an error code" `
    ($unknownEmail.Body.error.code -eq $wrongPassword.Body.error.code) `
    "$($unknownEmail.Body.error.code) vs $($wrongPassword.Body.error.code)"
Assert-That "unknown email and wrong password share an error message" `
    ($unknownEmail.Body.error.message -eq $wrongPassword.Body.error.message)

$login = Invoke-Api -Method POST -Path "/auth/login" -Body @{ email = $email; password = $password }
Assert-That "correct credentials return 200" ($login.Status -eq 200) "got $($login.Status)"
$sessionB = $login.Body.tokens

Section "4. Refresh rotation and reuse detection"
$rotated = Invoke-Api -Method POST -Path "/auth/refresh" -Body @{ refreshToken = $sessionB.refreshToken }
Assert-That "refresh returns 200" ($rotated.Status -eq 200) "got $($rotated.Status)"
Assert-That "a different refresh token comes back" ($rotated.Body.tokens.refreshToken -ne $sessionB.refreshToken)
$sessionBRotated = $rotated.Body.tokens

$replay = Invoke-Api -Method POST -Path "/auth/refresh" -Body @{ refreshToken = $sessionB.refreshToken }
Assert-That "replaying the consumed token returns 401" ($replay.Status -eq 401) "got $($replay.Status)"
Assert-That "replay is reported as TOKEN_REUSE_DETECTED" `
    ($replay.Body.error.code -eq "TOKEN_REUSE_DETECTED") "got $($replay.Body.error.code)"

# The successor was valid moments ago. A detected leak must kill the whole chain.
$afterBreach = Invoke-Api -Method POST -Path "/auth/refresh" -Body @{
    refreshToken = $sessionBRotated.refreshToken
}
Assert-That "the successor token is revoked too (whole family dies)" `
    ($afterBreach.Status -eq 401) "got $($afterBreach.Status)"

# Session A started a separate family at registration and must be unaffected.
$sessionAStillWorks = Invoke-Api -Method POST -Path "/auth/refresh" -Body @{
    refreshToken = $sessionA.refreshToken
}
Assert-That "an unrelated session's family survives the breach" `
    ($sessionAStillWorks.Status -eq 200) "got $($sessionAStillWorks.Status)"
$sessionA = $sessionAStillWorks.Body.tokens

Section "5. Change password"
$wrongCurrent = Invoke-Api -Method POST -Path "/auth/change-password" -AccessToken $sessionA.accessToken -Body @{
    currentPassword = "not-my-password-1"; newPassword = $newPassword
}
Assert-That "wrong current password returns 401" ($wrongCurrent.Status -eq 401) "got $($wrongCurrent.Status)"

$sameAgain = Invoke-Api -Method POST -Path "/auth/change-password" -AccessToken $sessionA.accessToken -Body @{
    currentPassword = $password; newPassword = $password
}
Assert-That "reusing the same password returns 400" ($sameAgain.Status -eq 400) "got $($sameAgain.Status)"

$changed = Invoke-Api -Method POST -Path "/auth/change-password" -AccessToken $sessionA.accessToken -Body @{
    currentPassword = $password; newPassword = $newPassword; refreshToken = $sessionA.refreshToken
}
Assert-That "change password returns 204" ($changed.Status -eq 204) "got $($changed.Status)"

$oldPassword = Invoke-Api -Method POST -Path "/auth/login" -Body @{ email = $email; password = $password }
Assert-That "the old password no longer works" ($oldPassword.Status -eq 401) "got $($oldPassword.Status)"

$newLogin = Invoke-Api -Method POST -Path "/auth/login" -Body @{ email = $email; password = $newPassword }
Assert-That "the new password works" ($newLogin.Status -eq 200) "got $($newLogin.Status)"

$keptSession = Invoke-Api -Method POST -Path "/auth/refresh" -Body @{ refreshToken = $sessionA.refreshToken }
Assert-That "the caller's own session survived the password change" `
    ($keptSession.Status -eq 200) "got $($keptSession.Status)"
$sessionA = $keptSession.Body.tokens

Section "6. Logout"
$loggedOut = Invoke-Api -Method POST -Path "/auth/logout" -Body @{ refreshToken = $sessionA.refreshToken }
Assert-That "logout returns 204" ($loggedOut.Status -eq 204) "got $($loggedOut.Status)"

$afterLogout = Invoke-Api -Method POST -Path "/auth/refresh" -Body @{ refreshToken = $sessionA.refreshToken }
Assert-That "the refresh token is dead after logout" ($afterLogout.Status -eq 401) "got $($afterLogout.Status)"

# Clients retry logout after network failures; it must never fail.
$logoutAgain = Invoke-Api -Method POST -Path "/auth/logout" -Body @{ refreshToken = $sessionA.refreshToken }
Assert-That "logout is idempotent" ($logoutAgain.Status -eq 204) "got $($logoutAgain.Status)"

$logoutUnknown = Invoke-Api -Method POST -Path "/auth/logout" -Body @{ refreshToken = "never-issued" }
Assert-That "logout tolerates an unknown token" ($logoutUnknown.Status -eq 204) "got $($logoutUnknown.Status)"

Section "7. Forgot password"
$forgotKnown = Invoke-Api -Method POST -Path "/auth/forgot-password" -Body @{ email = $email }
Assert-That "forgot-password returns 202 for a known address" ($forgotKnown.Status -eq 202) "got $($forgotKnown.Status)"

$forgotUnknown = Invoke-Api -Method POST -Path "/auth/forgot-password" -Body @{
    email = "ghost+$stamp@example.com"
}
Assert-That "forgot-password returns 202 for an unknown address too" `
    ($forgotUnknown.Status -eq 202) "got $($forgotUnknown.Status)"
Write-Host "        (check the server log for the reset token - the dev mail sender prints it)" -ForegroundColor DarkGray

Section "8. Rate limiting"
$rateEmail = "flood+$stamp@example.com"
$sawRateLimit = $false
for ($i = 1; $i -le 8; $i++) {
    $attempt = Invoke-Api -Method POST -Path "/auth/login" -Body @{ email = $rateEmail; password = "whatever-1" }
    if ($attempt.Status -eq 429) { $sawRateLimit = $true; break }
}
Assert-That "repeated login attempts are eventually rate limited (429)" $sawRateLimit

# ---------------------------------------------------------------------------

Write-Host "`n----------------------------------------" -ForegroundColor White
Write-Host "Passed: $script:Passed   Failed: $script:Failed" -ForegroundColor White
if ($script:Failed -gt 0) { exit 1 }
Write-Host "Phase 1 verified." -ForegroundColor Green
