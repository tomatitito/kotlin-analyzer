# Create v0.2.0 Release - HIGHEST PRIORITY

## Overview

The current version has working LSP features (hover, rename, go-to-definition, find references) and should be tagged and released as v0.2.0 to create a stable baseline we can return to if needed.

## Release Version

**v0.2.0** - Core LSP Features Working

## What's Working in This Release

### LSP Features
- ✅ Hover - Shows type information and signatures
- ✅ Rename - Updates all references correctly
- ✅ Go-to-Definition - Navigates to declarations
- ✅ Find References - Locates all usages
- ✅ Diagnostics - Shows errors and warnings

### Technical Improvements
- Request buffering during sidecar startup (30s timeout)
- Immediate cancellation on sidecar crash (no more 60s timeouts)
- Source root fallback for non-standard projects
- Fixed Zed extension grammar declaration
- Reliable binary discovery

### Testing
- 14 sidecar integration tests passing
- 35+ Rust unit tests passing
- Manual verification scripts included

## Known Issues
- Spring Boot annotations don't resolve (classpath issue)
- No Zed status bar integration yet
- Requires manual installation of binaries

## Release Steps

### 1. Build Release Artifacts
```bash
# Build Rust LSP binary
cargo build --release

# Build sidecar JAR
cd sidecar && ./gradlew shadowJar

# Create release directory
mkdir -p release/v0.2.0

# Copy artifacts
cp target/release/kotlin-analyzer release/v0.2.0/
cp sidecar/build/libs/sidecar-all.jar release/v0.2.0/sidecar.jar
```

### 2. Create Platform Archives
```bash
# macOS ARM64
tar -czf kotlin-analyzer-v0.2.0-macos-aarch64.tar.gz \
  -C release/v0.2.0 kotlin-analyzer sidecar.jar

# Linux x64 (if available)
# tar -czf kotlin-analyzer-v0.2.0-linux-x86_64.tar.gz \
#   -C release/v0.2.0 kotlin-analyzer sidecar.jar
```

### 3. Create Git Tag
```bash
# Create annotated tag
git tag -a v0.2.0 -m "Release v0.2.0: Core LSP features working

- Hover, rename, go-to-definition, find references working
- Request buffering and crash recovery implemented
- 14 integration tests passing
- Fixed grammar declaration and binary discovery"

# Push tag to GitHub
git push origin v0.2.0
```

### 4. Create GitHub Release
```bash
gh release create v0.2.0 \
  --title "v0.2.0: Core LSP Features" \
  --notes "## What's New

This release establishes a working baseline with core LSP features functional.

### Features
- **Hover**: Type information and signatures
- **Rename**: Symbol renaming across files
- **Go-to-Definition**: Navigate to declarations
- **Find References**: Find all symbol usages
- **Diagnostics**: Error and warning reporting

### Improvements
- Request buffering during sidecar startup
- Immediate crash recovery
- Source root fallback for non-standard projects
- Fixed Zed extension integration

### Installation

1. Download the appropriate archive for your platform
2. Extract to a directory on your PATH:
   \`\`\`bash
   tar -xzf kotlin-analyzer-v0.2.0-macos-aarch64.tar.gz
   cp kotlin-analyzer ~/.local/bin/
   cp sidecar.jar ~/.local/bin/
   \`\`\`
3. Install the Zed extension (dev mode for now)

### Known Issues
- Spring Boot annotations require additional classpath configuration
- Manual binary installation required

### Requirements
- JDK 11 or later
- Zed editor
- Kotlin project (Gradle or Maven)

### Testing
Includes 14 integration tests and manual verification scripts in \`scripts/\` directory." \
  kotlin-analyzer-v0.2.0-macos-aarch64.tar.gz
```

### 5. Verify Release
```bash
# Check that release was created
gh release view v0.2.0

# List releases
gh release list
```

## Rollback Plan

If issues arise in future development, we can always return to this version:

```bash
# Checkout the tagged version
git checkout v0.2.0

# Or reset to this version
git reset --hard v0.2.0

# Download released binaries
gh release download v0.2.0
```

## Timeline

**IMMEDIATE** - Create this release before making any further changes to ensure we have a stable baseline.

## Checklist

- [ ] Build release artifacts (Rust binary + sidecar JAR)
- [ ] Create platform archives (.tar.gz files)
- [ ] Create and push git tag (v0.2.0)
- [ ] Create GitHub release with binaries
- [ ] Verify release is accessible
- [ ] Update README to reference v0.2.0 as latest stable
