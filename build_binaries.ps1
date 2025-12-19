# Function to build for android using gomobile
function Build-Android {
    $OutputDir = "app\src\main\jniLibs"
    $LibDir = "app\libs"

    if (!(Test-Path -Path $LibDir)) {
        New-Item -ItemType Directory -Path $LibDir -Force
    }

    Write-Host "Building Android AAR (gomobile bind) for gobridge to $LibDir\goncvpn.aar..."

    # Ensure we are in the root directory context where gomobile can work best, usually pointing to the package
    # Since gobridge is a sub-module, we need to correct the import path or run from context
    
    # Set ANDROID_NDK_HOME explicitly if not set, trying to find it in ANDROID_HOME/ndk
    if (-not $env:ANDROID_NDK_HOME) {
        $PossibleNdk = Get-ChildItem -Path "$env:ANDROID_HOME\ndk" -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($PossibleNdk) {
            $env:ANDROID_NDK_HOME = $PossibleNdk.FullName
            Write-Host "Setting ANDROID_NDK_HOME to $env:ANDROID_NDK_HOME"
        }
        else {
            Write-Warning "Could not find NDK in $env:ANDROID_HOME\ndk. Gomobile might fail if not configured."
        }
    }
    else {
        Write-Host "ANDROID_NDK_HOME is set to $env:ANDROID_NDK_HOME"
    }

    cd gobridge
    # gomobile bind -target=android -o ..\$LibDir\goncvpn.aar .
    # Note: gomobile bind automatically builds for all supported Android architectures (arm, arm64, 386, amd64) by default or specified by -target
    
    # We need to make sure 'gobridge' module is tidy or correct. 
    # Just in case, let's run mod tidy
    go mod tidy
    
    # Specify -androidapi 21 to ensure compatibility with modern NDKs that dropped support for older APIs (project requires 21+)
    gomobile bind -target=android -androidapi 21 -o ..\app\libs\goncvpn.aar .
    
    if ($LASTEXITCODE -ne 0) { 
        Write-Error "Gomobile bind failed"
        cd ..
        exit 1 
    }
    
    cd ..
    Write-Host "Build complete: app\libs\goncvpn.aar"
}

# Build for Android
Build-Android
