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
gh workflow run release.yml --ref 0.2.0 -f version=0.2.0
```

Run it from the tag, not from `main`: the workflow checks out that ref, and the
version and the code must belong together.

Publishing is `AUTOMATIC`: the workflow uploads the bundle, the portal validates
it, and it goes to Maven Central without another press. Watch **Deployments**
until the row reads `PUBLISHED`; validation takes a few minutes and the
repository mirrors it shortly after.

If validation fails, the deployment stops as `FAILED` and nothing is published,
so the version is still free: drop the deployment, fix the problem, and tag a
new version (or re-run the same one). Central never replaces a version that did
publish, so a bad release is fixed by tagging forward, not by reusing the number.

## Publishing from a workstation

The same task works locally with the secrets in the environment:

```bash
export MAVEN_CENTRAL_USERNAME=…
export MAVEN_CENTRAL_PASSWORD=…
export SIGNING_KEY="$(cat private-key.asc)"
export SIGNING_PASSWORD=…
./gradlew publishAggregationToCentralPortal -PVERSION_NAME=0.2.0
```

That publishes immediately once validation passes, exactly like the workflow.

`nmcpZipAggregation` builds the bundle without uploading it — useful for checking
that the AAR, POM, sources, javadoc and signatures are all present:

```bash
./gradlew nmcpZipAggregation
unzip -l build/nmcp/zip/aggregation.zip
```

## What gets published

`io.github.mbanucu:android-accessible-terminal:<version>`, with the AAR, a sources
jar, a javadoc jar, Gradle module metadata, the POM and a `.asc` signature for
each. The POM carries the name, description, GPL-3.0-or-later license, developer
and SCM entries Central requires.
