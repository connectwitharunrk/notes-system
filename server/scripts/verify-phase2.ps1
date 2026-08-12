<#
    Phase 2 verification - notes CRUD plus the offline-first sync protocol.

    Exercises the parts that unit tests cannot reach: the native full-text
    search query, the row-locking change sequencer, JPA mappings for notes, and
    the two-device conflict flow end to end.

    Usage (server must already be running):
        .\scripts\verify-phase2.ps1
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
        $Body,
        [string]$AccessToken,
        [hashtable]$ExtraHeaders
    )

    $headers = @{ "X-Device-Platform" = "DESKTOP" }
    if ($AccessToken) { $headers["Authorization"] = "Bearer $AccessToken" }
    if ($ExtraHeaders) { foreach ($k in $ExtraHeaders.Keys) { $headers[$k] = $ExtraHeaders[$k] } }

    $params = @{
        Uri             = "$api$Path"
        Method          = $Method
        Headers         = $headers
        ContentType     = "application/json"
        UseBasicParsing = $true
    }
    if ($Body) { $params["Body"] = ($Body | ConvertTo-Json -Depth 10 -Compress) }

    try {
        $response = Invoke-WebRequest @params
        $parsed = $null
        if ($response.Content) { $parsed = $response.Content | ConvertFrom-Json -ErrorAction SilentlyContinue }
        return @{ Status = [int]$response.StatusCode; Body = $parsed }
    }
    catch {
        $webResponse = $_.Exception.Response
        if ($webResponse -eq $null) { return @{ Status = 0; Body = $null; Error = $_.Exception.Message } }

        # PowerShell 5.1 parks the error body here; the response stream is drained.
        $text = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $text = $_.ErrorDetails.Message }
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
function NewId { [guid]::NewGuid().ToString() }
function Now { [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ") }

<#
    Setup calls must never be piped to Out-Null. A silently failing arrangement
    step makes the assertion that follows report the wrong thing entirely - a
    500 on a setup push once made an unresolved conflict look like a clean apply.
#>
function Require-Push {
    param([string]$What, $Response, [string]$ExpectStatus = "APPLIED")

    if ($Response.Status -ne 200) {
        Write-Host "  SETUP FAILED  $What -> HTTP $($Response.Status)" -ForegroundColor Red
        if ($Response.Raw) { Write-Host "        $($Response.Raw)" -ForegroundColor DarkGray }
        $script:Failed++
        return $null
    }
    $r = $Response.Body.results[0]
    if ($r.status -ne $ExpectStatus) {
        Write-Host "  SETUP FAILED  $What -> $($r.status) (expected $ExpectStatus)" -ForegroundColor Red
        if ($r.error) { Write-Host "        $($r.error.code): $($r.error.message)" -ForegroundColor DarkGray }
        $script:Failed++
    }
    return $r
}

# Where-Object yields a scalar for a single match; @() forces an array so
# .Count is meaningful in every case.
function Count-Matching {
    param($Items, [string]$Id)
    return @($Items | Where-Object { $_.id -eq $Id }).Count
}

<#
    Raw request via curl.exe.

    Needed because Windows PowerShell 5.1's Invoke-WebRequest discards the
    response body when the request carried an If-Match header and the server
    answers 4xx - .NET treats a failed conditional request specially and
    ErrorDetails comes back null. The status is still reported, but the error
    envelope is unreachable, so any assertion about the error code silently
    fails. curl has no such behaviour.
#>
function Invoke-Curl {
    param([string]$Method, [string]$Path, [string]$AccessToken, [hashtable]$Headers, [string]$JsonBody)

    $curlArgs = @("-s", "-o", "-", "-w", "`n%{http_code}", "-X", $Method, "$api$Path")
    if ($AccessToken) { $curlArgs += @("-H", "Authorization: Bearer $AccessToken") }
    if ($Headers) { foreach ($k in $Headers.Keys) { $curlArgs += @("-H", "$k`: $($Headers[$k])") } }

    # The body goes via a temp file rather than -d "<json>": PowerShell rewrites
    # quotes when handing arguments to a native executable, which silently turns
    # valid JSON into an unparseable body and produces a misleading 400.
    $bodyFile = $null
    if ($JsonBody) {
        $bodyFile = [System.IO.Path]::GetTempFileName()
        [System.IO.File]::WriteAllText($bodyFile, $JsonBody, (New-Object System.Text.UTF8Encoding($false)))
        $curlArgs += @("-H", "Content-Type: application/json", "--data-binary", "@$bodyFile")
    }

    try {
        $raw = (& curl.exe @curlArgs) -join "`n"
    }
    finally {
        if ($bodyFile -and (Test-Path $bodyFile)) { Remove-Item $bodyFile -Force -ErrorAction SilentlyContinue }
    }

    $lines = $raw -split "`n"
    $status = [int]($lines[-1])
    $text = ""
    if ($lines.Count -gt 1) { $text = ($lines[0..($lines.Count - 2)] -join "`n").Trim() }
    $parsed = $null
    if ($text) { $parsed = $text | ConvertFrom-Json -ErrorAction SilentlyContinue }
    return @{ Status = $status; Body = $parsed; Raw = $text }
}

# ---------------------------------------------------------------------------

Write-Host "Verifying Phase 2 against $BaseUrl" -ForegroundColor White

$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$email = "notes+$stamp@example.com"
$deviceA = NewId
$deviceB = NewId

Section "0. Set up an account"
$register = Invoke-Api -Method POST -Path "/auth/register" -Body @{
    name = "Notes Tester"; email = $email; password = "correct-horse-1"
} -ExtraHeaders @{ "X-Device-Id" = $deviceA }
if ($register.Status -ne 201) {
    Write-Host "  Could not register (status $($register.Status)). Is the server running?" -ForegroundColor Red
    exit 1
}
$token = $register.Body.tokens.accessToken
Assert-That "registered a test user" ($register.Status -eq 201)

$headersA = @{ "X-Device-Id" = $deviceA }
$headersB = @{ "X-Device-Id" = $deviceB }

Section "1. Note CRUD"
$noteId = NewId
$created = Invoke-Api -Method POST -Path "/notes" -AccessToken $token -ExtraHeaders $headersA -Body @{
    id = $noteId; title = "Groceries"; content = "milk"; clientCreatedAt = (Now); clientUpdatedAt = (Now)
}
Assert-That "POST /notes returns 201" ($created.Status -eq 201) "got $($created.Status)"
Assert-That "the client-supplied id is honoured" ($created.Body.id -eq $noteId)
Assert-That "the first version is 1" ($created.Body.version -eq 1)
Assert-That "a change sequence was allocated" ($created.Body.changeSeq -ge 1)
Assert-That "a content hash was computed" ([string]::IsNullOrEmpty($created.Body.contentHash) -eq $false)

$again = Invoke-Api -Method POST -Path "/notes" -AccessToken $token -ExtraHeaders $headersA -Body @{
    id = $noteId; title = "Groceries"; content = "milk"; clientCreatedAt = (Now); clientUpdatedAt = (Now)
}
Assert-That "re-POSTing the same id is idempotent, not a conflict" `
    ($again.Status -eq 201 -and $again.Body.version -eq 1) "got $($again.Status) v$($again.Body.version)"

$fetched = Invoke-Api -Method GET -Path "/notes/$noteId" -AccessToken $token
Assert-That "GET /notes/{id} returns the note" ($fetched.Status -eq 200 -and $fetched.Body.content -eq "milk")

$listed = Invoke-Api -Method GET -Path "/notes?page=0&size=50" -AccessToken $token
Assert-That "GET /notes lists it" ($listed.Body.items.Count -ge 1) "got $($listed.Status)"
Assert-That "the page carries paging metadata" ($null -ne $listed.Body.totalElements)

$staleWrite = Invoke-Curl -Method PUT -Path "/notes/$noteId" -AccessToken $token `
    -Headers @{ "X-Device-Id" = $deviceA; "If-Match" = "99" } `
    -JsonBody '{"title":"Groceries","content":"nope"}'
Assert-That "a stale If-Match returns 409" ($staleWrite.Status -eq 409) "got $($staleWrite.Status)"
Assert-That "the 409 is reported as VERSION_CONFLICT" `
    ($staleWrite.Body.error.code -eq "VERSION_CONFLICT") `
    "code='$($staleWrite.Body.error.code)' raw='$($staleWrite.Raw)'"

$updated = Invoke-Curl -Method PUT -Path "/notes/$noteId" -AccessToken $token `
    -Headers @{ "X-Device-Id" = $deviceA; "If-Match" = "1" } `
    -JsonBody '{"title":"Groceries","content":"milk, eggs"}'
Assert-That "a matching If-Match succeeds" ($updated.Status -eq 200) "got $($updated.Status)"
Assert-That "the version was bumped" ($updated.Body.version -eq 2) "got $($updated.Body.version)"

Section "2. Pin, archive and search"
$pinned = Invoke-Api -Method POST -Path "/notes/$noteId/pin" -AccessToken $token -ExtraHeaders $headersA
Assert-That "pin works" ($pinned.Status -eq 200 -and $pinned.Body.isPinned -eq $true)

$archived = Invoke-Api -Method POST -Path "/notes/$noteId/archive" -AccessToken $token -ExtraHeaders $headersA
Assert-That "archive works" ($archived.Status -eq 200 -and $archived.Body.isArchived -eq $true)

$activeList = Invoke-Api -Method GET -Path "/notes?archived=false" -AccessToken $token
Assert-That "an archived note leaves the active list" `
    ((Count-Matching $activeList.Body.items $noteId) -eq 0) `
    "ids: $($activeList.Body.items.id -join ',')"

$archivedList = Invoke-Api -Method GET -Path "/notes?archived=true" -AccessToken $token
Assert-That "it appears in the archived list" `
    ((Count-Matching $archivedList.Body.items $noteId) -eq 1) `
    "looking for $noteId, got: $($archivedList.Body.items.id -join ',')"

$unarchived = Invoke-Api -Method POST -Path "/notes/$noteId/unarchive" -AccessToken $token -ExtraHeaders $headersA
Assert-That "unarchive works" ($unarchived.Status -eq 200) "got $($unarchived.Status)"

# Native Postgres FTS against the GIN index - never exercised by unit tests.
$found = Invoke-Api -Method GET -Path "/notes/search?q=eggs" -AccessToken $token
Assert-That "full-text search finds a word in the content" `
    ($found.Status -eq 200 -and (Count-Matching $found.Body.items $noteId) -eq 1) `
    "looking for $noteId, got: $($found.Body.items.id -join ',')"

$notFound = Invoke-Api -Method GET -Path "/notes/search?q=zzzznotpresent" -AccessToken $token
Assert-That "search returns nothing for an absent term" (@($notFound.Body.items).Count -eq 0)

$blank = Invoke-Api -Method GET -Path "/notes/search?q=%20" -AccessToken $token
Assert-That "a blank search query is rejected" ($blank.Status -eq 400) "got $($blank.Status)"

Section "3. Soft delete and restore"
$deleted = Invoke-Api -Method DELETE -Path "/notes/$noteId" -AccessToken $token -ExtraHeaders $headersA
Assert-That "DELETE returns 204" ($deleted.Status -eq 204) "got $($deleted.Status)"

$afterDelete = Invoke-Api -Method GET -Path "/notes/$noteId" -AccessToken $token
Assert-That "a deleted note is no longer readable" ($afterDelete.Status -eq 404) "got $($afterDelete.Status)"

$restored = Invoke-Api -Method POST -Path "/notes/$noteId/restore" -AccessToken $token -ExtraHeaders $headersA
Assert-That "restore brings it back" ($restored.Status -eq 200 -and $restored.Body.isDeleted -eq $false)

Section "4. Sync status and pull"
$status = Invoke-Api -Method GET -Path "/sync/status?cursor=0" -AccessToken $token
Assert-That "GET /sync/status returns 200" ($status.Status -eq 200) "got $($status.Status)"
Assert-That "the server cursor has advanced" ($status.Body.serverCursor -gt 0)
Assert-That "the tombstone floor starts at 0" ($status.Body.tombstoneFloor -eq 0)

$fullPull = Invoke-Api -Method GET -Path "/sync/pull?cursor=0" -AccessToken $token
Assert-That "a full pull returns 200" ($fullPull.Status -eq 200) "got $($fullPull.Status)"
Assert-That "it delivers the note" ((Count-Matching $fullPull.Body.notes $noteId) -eq 1) `
    "looking for $noteId, got: $($fullPull.Body.notes.id -join ',')"
Assert-That "it is not asked to resync" ($fullPull.Body.resyncRequired -eq $false)
Assert-That "nextCursor advanced past 0" ($fullPull.Body.nextCursor -gt 0)

$emptyPull = Invoke-Api -Method GET -Path "/sync/pull?cursor=$($fullPull.Body.nextCursor)" -AccessToken $token
Assert-That "pulling from the tip returns nothing" ($emptyPull.Body.notes.Count -eq 0)
Assert-That "an empty pull leaves the cursor unchanged" `
    ($emptyPull.Body.nextCursor -eq $fullPull.Body.nextCursor)

Section "5. Sync push"
$syncId = NewId
$pushed = Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(@{
        id = $syncId; baseVersion = 0; title = "Synced"; content = "created offline"
        clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}
Assert-That "push returns 200" ($pushed.Status -eq 200) "got $($pushed.Status)"
$result = $pushed.Body.results[0]
Assert-That "an offline creation is APPLIED" ($result.status -eq "APPLIED") "got $($result.status)"
Assert-That "it starts at version 1" ($result.version -eq 1)
Assert-That "it received a change sequence" ($result.changeSeq -gt 0)

$syncedNote = (Invoke-Api -Method GET -Path "/notes/$syncId" -AccessToken $token).Body
$baseHash = $syncedNote.contentHash

$cleanUpdate = Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(@{
        id = $syncId; baseVersion = 1; title = "Synced"; content = "edited once"
        baseContentHash = $baseHash; clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}
Assert-That "a matching baseVersion applies cleanly" `
    ($cleanUpdate.Body.results[0].status -eq "APPLIED" -and $cleanUpdate.Body.results[0].version -eq 2)

$tooBig = Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(@{
        id = (NewId); baseVersion = 0; title = "Huge"; content = ("x" * 600000)
        clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}
Assert-That "an oversized note is REJECTED" ($tooBig.Body.results[0].status -eq "REJECTED") `
    "got $($tooBig.Body.results[0].status)"
Assert-That "the rejection names NOTE_TOO_LARGE" ($tooBig.Body.results[0].error.code -eq "NOTE_TOO_LARGE")

$dupId = NewId
$dupes = Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(
        @{ id = $dupId; baseVersion = 0; title = "A"; content = "one"; clientCreatedAt = (Now); clientUpdatedAt = (Now) },
        @{ id = $dupId; baseVersion = 0; title = "A"; content = "two"; clientCreatedAt = (Now); clientUpdatedAt = (Now) }
    )
}
Assert-That "a duplicate id in one batch is rejected" `
    ($dupes.Body.results[0].status -eq "APPLIED" -and $dupes.Body.results[1].status -eq "REJECTED")

Section "6. Two-device conflict"
$conflictId = NewId
Require-Push "create the shared note" (Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(@{
        id = $conflictId; baseVersion = 0; title = "Shared"; content = "milk"
        clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}) | Out-Null
$sharedBase = (Invoke-Api -Method GET -Path "/notes/$conflictId" -AccessToken $token).Body.contentHash

# Device B syncs first and wins the race. Note it has never logged in on this
# install, which is exactly the unregistered-device case.
Require-Push "device B wins the race" (Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceB
    changes  = @(@{
        id = $conflictId; baseVersion = 1; title = "Shared"; content = "milk, bread"
        baseContentHash = $sharedBase; clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}) | Out-Null

$cursorBeforeConflict = (Invoke-Api -Method GET -Path "/sync/status?cursor=0" -AccessToken $token).Body.serverCursor

# Device A pushes an edit derived from the same base it last saw.
$conflict = Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(@{
        id = $conflictId; baseVersion = 1; title = "Shared"; content = "milk, eggs"
        baseContentHash = $sharedBase; clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}
$cr = $conflict.Body.results[0]
Assert-That "a diverged push is reported as CONFLICT" ($cr.status -eq "CONFLICT") "got $($cr.status)"
Assert-That "the resolution is CONFLICT_COPY_CREATED" ($cr.resolution -eq "CONFLICT_COPY_CREATED") `
    "got $($cr.resolution)"
Assert-That "the server copy is returned untouched" ($cr.server.content -eq "milk, bread") `
    "got '$($cr.server.content)'"
Assert-That "a conflict copy was created" ($null -ne $cr.conflictCopy)
Assert-That "the copy preserves the losing text" ($cr.conflictCopy.content -eq "milk, eggs") `
    "got '$($cr.conflictCopy.content)'"
Assert-That "the copy is titled as a conflict copy" ($cr.conflictCopy.title -like "*conflict copy*")
Assert-That "the copy links back to the original" ($cr.conflictCopy.conflictOf -eq $conflictId)
Assert-That "the copy is not born deleted" ($cr.conflictCopy.isDeleted -eq $false)

$conflictPull = Invoke-Api -Method GET -Path "/sync/pull?cursor=$cursorBeforeConflict" -AccessToken $token
Assert-That "the conflict copy reaches other devices on the next pull" `
    ((Count-Matching $conflictPull.Body.notes $cr.conflictCopy.id) -eq 1) `
    "looking for $($cr.conflictCopy.id), got: $($conflictPull.Body.notes.id -join ',')"

Section "7. Metadata merge and delete-versus-edit"
$metaId = NewId
Require-Push "create the metadata note" (Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(@{
        id = $metaId; baseVersion = 0; title = "Meta"; content = "same text"
        clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}) | Out-Null
$metaHash = (Invoke-Api -Method GET -Path "/notes/$metaId" -AccessToken $token).Body.contentHash

Require-Push "device B pins it" (Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceB
    changes  = @(@{
        id = $metaId; baseVersion = 1; title = "Meta"; content = "same text"; isPinned = $true
        baseContentHash = $metaHash; clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}) | Out-Null

# Same text, stale version, archive toggled - must merge, not conflict.
$meta = Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(@{
        id = $metaId; baseVersion = 1; title = "Meta"; content = "same text"; isArchived = $true
        baseContentHash = $metaHash; clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}
$mr = $meta.Body.results[0]
Assert-That "identical text with different flags merges instead of conflicting" `
    ($mr.resolution -eq "METADATA_MERGED") "got $($mr.resolution)"
Assert-That "no conflict copy is created for a metadata-only difference" ($null -eq $mr.conflictCopy)

$finalMeta = (Invoke-Api -Method GET -Path "/notes/$metaId" -AccessToken $token).Body
Assert-That "the pin from the other device survived the merge" ($finalMeta.isPinned -eq $true)

$delId = NewId
Require-Push "create the raced note" (Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(@{
        id = $delId; baseVersion = 0; title = "Race"; content = "original"
        clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}) | Out-Null
$delHash = (Invoke-Api -Method GET -Path "/notes/$delId" -AccessToken $token).Body.contentHash

# Device B deletes it.
Require-Push "device B deletes it" (Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceB
    changes  = @(@{
        id = $delId; baseVersion = 1; title = "Race"; content = "original"; isDeleted = $true
        baseContentHash = $delHash; clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}) | Out-Null

# Device A had meanwhile written something into it.
$race = Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(@{
        id = $delId; baseVersion = 1; title = "Race"; content = "an important paragraph"
        baseContentHash = $delHash; clientCreatedAt = (Now); clientUpdatedAt = (Now)
    })
}
Assert-That "an edit beats a concurrent delete" `
    ($race.Body.results[0].resolution -eq "EDIT_WINS_OVER_DELETE") "got $($race.Body.results[0].resolution)"

$survivor = (Invoke-Api -Method GET -Path "/notes/$delId" -AccessToken $token)
Assert-That "the note is alive again" ($survivor.Status -eq 200) "got $($survivor.Status)"
Assert-That "the edited text survived" ($survivor.Body.content -eq "an important paragraph")

Section "8. Sequencing and isolation"
$before = (Invoke-Api -Method GET -Path "/sync/status?cursor=0" -AccessToken $token).Body.serverCursor
$batch = Invoke-Api -Method POST -Path "/sync/push" -AccessToken $token -Body @{
    deviceId = $deviceA
    changes  = @(
        @{ id = (NewId); baseVersion = 0; title = "s1"; content = "1"; clientCreatedAt = (Now); clientUpdatedAt = (Now) },
        @{ id = (NewId); baseVersion = 0; title = "s2"; content = "2"; clientCreatedAt = (Now); clientUpdatedAt = (Now) },
        @{ id = (NewId); baseVersion = 0; title = "s3"; content = "3"; clientCreatedAt = (Now); clientUpdatedAt = (Now) }
    )
}
$seqs = $batch.Body.results | ForEach-Object { $_.changeSeq }
Assert-That "sequences within a batch are consecutive" `
    ($seqs[0] -eq $before + 1 -and $seqs[1] -eq $before + 2 -and $seqs[2] -eq $before + 3) `
    "got $($seqs -join ', ') after $before"
Assert-That "the reported cursor matches the last sequence" `
    ($batch.Body.serverCursor -eq $before + 3)

# A second account must not see any of this.
$otherEmail = "other+$stamp@example.com"
$other = Invoke-Api -Method POST -Path "/auth/register" -Body @{
    name = "Other"; email = $otherEmail; password = "correct-horse-1"
} -ExtraHeaders @{ "X-Device-Id" = (NewId) }
$otherPull = Invoke-Api -Method GET -Path "/sync/pull?cursor=0" -AccessToken $other.Body.tokens.accessToken
Assert-That "a different account pulls none of these notes" ($otherPull.Body.notes.Count -eq 0) `
    "got $($otherPull.Body.notes.Count)"

$stolen = Invoke-Api -Method GET -Path "/notes/$noteId" -AccessToken $other.Body.tokens.accessToken
Assert-That "another account cannot read this note by id" ($stolen.Status -eq 404) "got $($stolen.Status)"

# ---------------------------------------------------------------------------

Write-Host "`n----------------------------------------" -ForegroundColor White
Write-Host "Passed: $script:Passed   Failed: $script:Failed" -ForegroundColor White
if ($script:Failed -gt 0) { exit 1 }
Write-Host "Phase 2 verified." -ForegroundColor Green
