# Types the PC clipboard into the focused text field on the emulator / TV over adb.
# Usage: copy the text, focus the field on the device, run this script.
$adb = "$env:USERPROFILE\android-dev\sdk\platform-tools\adb.exe"
$text = (Get-Clipboard -Raw).Trim()
# `input text` runs through the device's sh: escape shell metacharacters, and spaces become %s.
$escaped = ($text -replace '([\\"''`$&|;<>()*?!#~^\[\]{}])', '\$1') -replace ' ', '%s'
& $adb shell input text $escaped
