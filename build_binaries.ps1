# Function to build for a specific arch
function Build-Arch ($GoArch, $JniArch) {
    $OutputDir = "app\src\main\jniLibs\$JniArch"
    if (!(Test-Path -Path $OutputDir)) {
        New-Item -ItemType Directory -Path $OutputDir -Force
    }
    
    $env:GOARCH = $GoArch
    Write-Host "Building for $GoArch -> $JniArch..."

    cd gonc
    go build -mod=vendor -buildmode=pie -o ..\$OutputDir\libgonc.so -ldflags "-s -w" .
    if ($LASTEXITCODE -ne 0) { Write-Error "Build gonc ($GoArch) failed"; exit 1 }
    cd ..

    cd tun2socks
    go build -buildmode=pie -o ..\$OutputDir\libtun2socks.so -ldflags "-s -w" .
    if ($LASTEXITCODE -ne 0) { Write-Error "Build tun2socks ($GoArch) failed"; exit 1 }
    cd ..
}

# Build for supported architectures
Build-Arch "arm64" "arm64-v8a"
# Build-Arch "amd64" "x86_64" # Requires NDK/CGO, distinct environment

Write-Host "Build complete."
