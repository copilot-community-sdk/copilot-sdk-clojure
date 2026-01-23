# Publishing to Maven Central

This guide explains how to publish the Copilot SDK to Maven Central for use in Java/Maven projects.

## Prerequisites

### 1. Create Sonatype OSSRH Account

**Option A: Legacy OSSRH (Recommended for now)**

1. Create account at https://issues.sonatype.org/
2. Create a new project ticket to claim your namespace (io.github.krukow)
3. Wait for approval (usually 1-2 business days)

**Option B: New Central Portal**

1. Go to https://central.sonatype.com/
2. Sign in with GitHub
3. Namespace is automatically verified for `io.github.USERNAME`

### 2. Configure Maven Settings

Create or edit `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_SONATYPE_USERNAME</username>
      <password>YOUR_SONATYPE_PASSWORD</password>
    </server>
  </servers>
</settings>
```

For the legacy OSSRH, use your Jira credentials.
For the new Central Portal, generate a token at https://central.sonatype.com/account.

### 3. Configure GPG for Signing

Maven Central requires signed artifacts. Set up GPG:

```bash
# Generate a key if you don't have one
gpg --gen-key
```

Find your key ID:

```bash
gpg --list-secret-keys --keyid-format LONG
```

Output looks like:
```
sec   rsa4096/ABC123DEF4567890 2024-01-15 [SC]
      FULL40CHARFINGERPRINT1234567890ABCDEF12
uid                 [ultimate] Your Name <your@email.com>
ssb   rsa4096/XYZ789GHI0123456 2024-01-15 [E]
```

The key ID is `ABC123DEF4567890` (the hex string after `rsa4096/` on the `sec` line).

Upload your public key to keyservers (required for verification):

```bash
# Upload to Ubuntu keyserver
gpg --keyserver keyserver.ubuntu.com --send-keys ABC123DEF4567890

# Also upload to MIT keyserver for redundancy
gpg --keyserver pgp.mit.edu --send-keys ABC123DEF4567890
```

If you have multiple GPG keys, set the default for signing:

```bash
# Option 1: Set default in gpg.conf
echo "default-key ABC123DEF4567890" >> ~/.gnupg/gpg.conf

# Option 2: Set via environment variable
export GPG_KEY_ID=ABC123DEF4567890
```

Test that signing works:

```bash
echo "test" | gpg --clearsign
```

## Publishing

### Local Testing

Before publishing, test locally:

```bash
# Build and install to local Maven repo (~/.m2)
clj -T:build aot-jar
clj -T:build install

# Test with Java examples
cd examples/java
mvn compile exec:java
```

### Production Release

The new Sonatype Central Portal requires uploading a bundle zip:

```bash
# Create signed bundle with all required artifacts
clj -T:build bundle :version '"0.1.0"'
```

This creates `target/copilot-sdk-0.1.0-bundle.zip` containing:
- Main JAR (`copilot-sdk-0.1.0.jar`)
- Sources JAR (`copilot-sdk-0.1.0-sources.jar`)
- Javadoc JAR (`copilot-sdk-0.1.0-javadoc.jar`)
- POM file (`copilot-sdk-0.1.0.pom`)
- GPG signatures (`.asc` files)
- Checksums (`.md5` and `.sha1` files)

Then upload manually:

1. Go to https://central.sonatype.com/publishing
2. Click "Publish Component"
3. Upload the bundle zip file
4. Wait for validation (checks signatures, checksums, required files)
5. Click "Publish" to release

**Note:** The Central Portal does not support SNAPSHOT versions. For development testing, use local install:

```bash
clj -T:build aot-jar
clj -T:build install
```

## Maven Coordinates

Once published, Java projects can use:

```xml
<dependency>
    <groupId>io.github.krukow</groupId>
    <artifactId>copilot-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

With required dependencies:

```xml
<!-- Clojure runtime -->
<dependency>
    <groupId>org.clojure</groupId>
    <artifactId>clojure</artifactId>
    <version>1.12.4</version>
</dependency>

<!-- Core.async -->
<dependency>
    <groupId>org.clojure</groupId>
    <artifactId>core.async</artifactId>
    <version>1.8.741</version>
</dependency>

<!-- Cheshire JSON -->
<dependency>
    <groupId>cheshire</groupId>
    <artifactId>cheshire</artifactId>
    <version>6.1.0</version>
</dependency>
```

Or use the examples/java/pom.xml as a reference for all required dependencies.

## Build Targets

| Command | Description |
|---------|-------------|
| `clj -T:build jar` | Source-only JAR (for Clojure projects) |
| `clj -T:build aot-jar` | AOT-compiled JAR (for Java projects) |
| `clj -T:build uber` | Standalone uberjar with all deps |
| `clj -T:build install` | Install to local Maven repo |
| `clj -T:build deploy` | Deploy to Clojars |
| `clj -T:build deploy-aot` | Deploy AOT JAR to Maven Central |
| `clj -T:build release :version '"X.Y.Z"'` | Full release to Maven Central |

## Troubleshooting

### GPG Signing Issues

If GPG signing fails:

```bash
# Check GPG agent is running
gpgconf --launch gpg-agent

# Test signing
echo "test" | gpg --clearsign

# If on macOS, you may need pinentry-mac
brew install pinentry-mac
echo "pinentry-program $(which pinentry-mac)" >> ~/.gnupg/gpg-agent.conf
gpgconf --kill gpg-agent
```

### Authentication Issues

If deployment fails with 401/403:

1. Verify your token is correct
2. Check namespace ownership at https://central.sonatype.com/
3. Ensure env vars are set: `echo $CLOJARS_USERNAME`

### Artifact Not Found After Deploy

Maven Central sync can take 10-30 minutes. Check:
- https://central.sonatype.com/ - search for your artifact
- https://repo1.maven.org/maven2/io/github/krukow/copilot-sdk/
