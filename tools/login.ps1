# Logs Kukatko in on the emulator / TV with the account from %USERPROFILE%\.kukatko.env:
#   O2_USER=you@example.com
#   O2_PASS=...
# The account goes over adb as base64, so no typing with a remote and no shell escaping.
$adb = "$env:USERPROFILE\android-dev\sdk\platform-tools\adb.exe"
$cfg = @{}
Get-Content "$HOME\.kukatko.env" | ForEach-Object {
    $key, $value = $_ -split '=', 2
    if ($value) { $cfg[$key.Trim()] = $value }
}
if (-not $cfg.O2_USER -or -not $cfg.O2_PASS) { throw "Put O2_USER= and O2_PASS= into $HOME\.kukatko.env" }
$payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($cfg.O2_USER)`n$($cfg.O2_PASS)"))
& $adb shell am start -n cz.ivo.kukatko/.MainActivity --es login $payload
