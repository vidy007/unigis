# Windows counterpart of build-apk.sh: the same steps without AGP, using the Android SDK's
# build-tools (aapt, d8, zipalign, apksigner), an NDK (only its clang), Gradle and a JDK.
#   powershell -ExecutionPolicy Bypass -File lite\build-apk.ps1
# Optional environment: ANDROID_SDK, ANDROID_NDK_HOME, JAVA_HOME, VERSION_NAME, VERSION_CODE, ABIS.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Need($path, $what) { if (-not $path -or -not (Test-Path $path)) { throw "$what not found: $path" }; $path }
function Run($exe) {
    & $exe @args
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path $exe -Leaf) failed with exit code $LASTEXITCODE" }
}

$sdk = if ($env:ANDROID_SDK) { $env:ANDROID_SDK } else { "$env:LOCALAPPDATA\Android\Sdk" }
$buildTools = Need (Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1).FullName 'build-tools'
$androidJar = Need (Get-ChildItem "$sdk\platforms\android-*\android.jar" | Sort-Object { [int]($_.Directory.Name -replace '\D') } | Select-Object -Last 1).FullName 'android.jar'
$ndk = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { (Get-ChildItem "$sdk\ndk" -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1).FullName }
$clang = Need "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe" 'NDK clang++'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_JAR = $androidJar

$abis = if ($env:ABIS) { $env:ABIS -split '\s+' } else { 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86' }
$targets = @{ 'arm64-v8a' = 'aarch64-linux-android'; 'armeabi-v7a' = 'armv7a-linux-androideabi'; 'x86_64' = 'x86_64-linux-android'; 'x86' = 'i686-linux-android' }
$minSdk = 26
$core = '..\app\src\main\cpp'
$out = 'build\apk'
# Same version as the main app unless overridden.
$props = Get-Content ..\gradle.properties
function Prop($key) { ($props | Where-Object { $_ -like "$key=*" } | Select-Object -First 1) -replace "^$([regex]::Escape($key))=", '' }
$versionName = if ($env:VERSION_NAME) { $env:VERSION_NAME } else { Prop 'unigis.versionName' }
$versionCode = if ($env:VERSION_CODE) { $env:VERSION_CODE } else { Prop 'unigis.versionCode' }
$apkName = "unigis-lite-$versionName.apk"

Remove-Item -Recurse -Force $out, 'build\gen', 'build\native', 'build\dex' -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out, 'build\gen', 'build\native', 'build\dex' | Out-Null

Write-Host "> clang++: libunigis.so ($($abis -join ' '))"
$sources = @(Get-ChildItem "$core\src\*.cpp" | ForEach-Object FullName) + "$core\jni\jni_bridge.cpp"
foreach ($abi in $abis) {
    New-Item -ItemType Directory -Force "build\native\lib\$abi" | Out-Null
    # Static libc++ keeps the APK to one .so per ABI; 16 KB pages for Android 15+ devices.
    Run $clang "--target=$($targets[$abi])$minSdk" -std=c++20 -O2 -fPIC -shared -Wall -Wextra `
        -fvisibility=hidden -ffunction-sections -fdata-sections `
        -static-libstdc++ '-Wl,--gc-sections' '-Wl,-z,max-page-size=16384' -s `
        -I "$core\include" @sources -llog -o "build\native\lib\$abi\libunigis.so"
}

Write-Host '> aapt: resources + assets + R.java'
Run "$buildTools\aapt.exe" package -f -m --replace-version -M AndroidManifest.xml -S res -A ..\app\src\main\assets `
    -I $androidJar -J build\gen -F "$out\unsigned.apk" --version-code $versionCode --version-name $versionName

Write-Host '> kotlinc (Gradle)'
$gradle = if ($env:GRADLE) { $env:GRADLE } else { (Resolve-Path ..\gradlew.bat).Path }
# No daemon: a lingering daemon keeps this script's output pipe open when it is piped.
Run $gradle -q --no-daemon -p . jar copyRuntime

Write-Host '> d8: classes.dex'
Run "$buildTools\d8.bat" --release --min-api $minSdk --lib $androidJar --output build\dex `
    build\libs\unigis-lite.jar @(Get-ChildItem build\runtime\*.jar | ForEach-Object FullName)

Write-Host '> package + align + sign'
Copy-Item build\dex\classes.dex "$out\classes.dex"
Push-Location $out; try { Run "$buildTools\aapt.exe" add -f unsigned.apk classes.dex | Out-Null } finally { Pop-Location }
Push-Location build\native
try { Run "$buildTools\aapt.exe" add -f "..\..\$out\unsigned.apk" @($abis | ForEach-Object { "lib/$_/libunigis.so" }) | Out-Null }
finally { Pop-Location }
Run "$buildTools\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
Run "$buildTools\apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android `
    --out "$out\$apkName" "$out\aligned.apk"
Run "$buildTools\apksigner.bat" verify "$out\$apkName"
Write-Host "OK: $(Resolve-Path "$out\$apkName")"
