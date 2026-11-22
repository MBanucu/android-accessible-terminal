# Releasing

Releases go to [Maven Central](https://central.sonatype.com) through the Central
Portal API, published by `.github/workflows/release.yml`. JitPack also builds
every tag, which stays available as a fallback.

The first release needs a one-time account setup and four repository secrets.
None of it is in the repository.

## One-time setup

### 1. A Central Portal account and namespace

1. Sign in at <https://central.sonatype.com> with GitHub.
2. Add the namespace `io.github.mbanucu` and complete the GitHub verification the
   portal offers. The `io.github.<user>` form is verified against the GitHub
   account, which is why the coordinates are `io.github.mbanucu:<artifactId>` and
   not `com.github.MBanucu`.

### 2. A publishing token

In the portal, under **Publishing Settings → User Token**, create a token. It has
two parts, a username and a password; those become `MAVEN_CENTRAL_USERNAME` and
`MAVEN_CENTRAL_PASSWORD`. The account password does not work for publishing.

### 3. A PGP key

Central requires a detached signature on every file. A dedicated key is better
than a personal one:

```bash
gpg --quick-generate-key "MBanucu <MBanucu@users.noreply.github.com>" rsa4096 sign 2y
keyid=$(gpg --list-secret-keys --with-colons | awk -F: '/^sec:/ {print $5; exit}')
gpg --armor --export "$keyid" > public-key.asc
gpg --armor --export-secret-keys "$keyid" > private-key.asc
```

Upload `public-key.asc` to <https://keys.openpgp.org> and confirm the address.
Central validates signatures against that keyserver; a key that was never
uploaded makes the deployment fail validation with "signature key not found".

```bash
gpg --keyserver keys.openpgp.org --send-keys "$keyid"
```

Keep `private-key.asc` out of the repository and delete it once it is a secret.

### 4. The repository secrets

Add these under **Settings → Secrets and variables → Actions**:

| secret | value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | the user token's username |
| `MAVEN_CENTRAL_PASSWORD` | the user token's password |
| `SIGNING_KEY` | the contents of `private-key.asc` (armored, whole file) |
| `SIGNING_PASSWORD` | the key's passphrase, or an empty string if it has none |

```bash
gh secret set MAVEN_CENTRAL_USERNAME
gh secret set MAVEN_CENTRAL_PASSWORD
gh secret set SIGNING_KEY < private-key.asc
gh secret set SIGNING_PASSWORD
```

## Publishing a version

Central never allows a version to be replaced, so the version is taken from the
tag:

```bash
git tag 0.2.0
git push origin 0.2.0
gh release create 0.2.0 --title "0.2.0" --notes "…"
```

Publishing the GitHub release starts the workflow. To retry a failed run without
creating another tag:

```bash
gh workflow run release.yml -f version=0.2.0
```

The build is `USER_MANAGED`, so the deployment lands in the portal as a draft:
open **Deployments**, check the validation, and press **Publish**. Once a release
has gone through without manual fixes, change `publishingType` to `AUTOMATIC` in
`build.gradle.kts` and the portal publishes after validation on its own.

## Publishing from a workstation

The same task works locally with the secrets in the environment:

```bash
export MAVEN_CENTRAL_USERNAME=…
export MAVEN_CENTRAL_PASSWORD=…
export SIGNING_KEY="$(cat private-key.asc)"
export SIGNING_PASSWORD=…
./gradlew publishAggregationToCentralPortal -PVERSION_NAME=0.2.0
```

`nmcpZipAggregation` builds the bundle without uploading it — useful for checking
that the AAR, POM, sources, javadoc and signatures are all present:

```bash
./gradlew nmcpZipAggregation
unzip -l build/nmcp/zip/aggregation.zip
```

## What gets published

`io.github.mbanucu:android-accessible-terminal:<version>`, with the AAR, a sources
jar, a javadoc jar, Gradle module metadata, the POM and a `.asc` signature for
each. The POM carries the name, description, Apache-2.0 license, developer and
SCM entries Central requires.
