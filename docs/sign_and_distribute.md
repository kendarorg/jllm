# Sign and distribute to Maven Central

Personal reference. Not linked from anywhere.

## How it works

- Signing and publishing live in the `deploy` profile of the root `pom.xml`.
- `maven-gpg-plugin` signs at the `verify` phase (`sign-artifacts`).
- `central-publishing-maven-plugin` uploads to the Central Portal using the server id `central` from `~/.m2/settings.xml`.
- Without `-Pdeploy` nothing is signed or published. For a plain local build, omit the profile or pass `-Dgpg.skip`.
- Maven Central has no key registration. It verifies signatures against public keyservers.

## 1. Create the GPG key (once)

Kleopatra (File > New OpenPGP Key Pair) or CLI. Both use the same keyring (`%APPDATA%\gnupg`).

```
gpg --full-generate-key
```

- Type: RSA 4096
- Expiry: 0 (none) or a date
- Name/email: an address you control. The key is public. Use the `pom.xml` developer email.
- Set a passphrase.

## 2. Find the key ID

```
gpg --list-secret-keys --keyid-format long
```

```
sec   rsa4096/3AA5C34371567BD2 2026-11-17 [SC]
      1234567890ABCDEF1234567890ABCDEF12345678
uid                 [ultimate] Your Name <you@example.com>
```

`<KEYID>` is `3AA5C34371567BD2` (after `rsa4096/`) or the full 40-char fingerprint. Omit the angle brackets in commands.

Empty output means no secret key exists. This was the cause of `gpg: no default secret key`.

## 3. Back up (do not skip)

```
gpg --export-secret-keys --armor <KEYID> > jllm-key.asc
gpg --gen-revoke <KEYID> > jllm-revoke.asc
```

- Store both in a password manager or encrypted drive.
- Never commit them.
- Restore on another machine with `gpg --import jllm-key.asc`.

## 4. Publish the public key

```
gpg --keyserver keys.openpgp.org --send-keys <KEYID>
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
```

- keys.openpgp.org emails a verification link. Click it, or the key is not searchable by email.
- Central looks keys up on keys.openpgp.org, keyserver.ubuntu.com and pgp.mit.edu.
- Propagation can take minutes. "Could not find public key" or "Invalid signature" in Central usually means it has not propagated yet.

## 5. Passphrase

- Set `MAVEN_GPG_PASSPHRASE` as an environment variable, or rely on the gpg agent (pinentry prompt).
- Remove any passphrase stored in `~/.m2/settings.xml` (Maven warns: "Sensitive content loaded from settings.xml").
- Several keys: add `-Dgpg.keyname=<KEYID>`.

## 6. Central Portal credentials

- https://central.sonatype.com, sign in with the same account used before.
- The namespace must be verified (Namespaces). Do not create a new account, the namespace is tied to the old one.
- Account > Generate User Token.
- Put the token in `~/.m2/settings.xml`:

```xml
<server>
  <id>central</id>
  <username>TOKEN_USERNAME</username>
  <password>TOKEN_PASSWORD</password>
</server>
```

This is the token, not the GitHub or Portal password.

## 7. Release

1. Set the version (no SNAPSHOT).
2. Run:
   ```
   mvn -Pdeploy clean deploy
   ```
   Use JDK 21 (`JAVA_HOME=~/.jdks/corretto-21.0.9`).
3. The build creates `.asc` files and uploads the bundle.
4. In the Portal > Deployments: wait for validation, then press Publish (unless `autoPublish` is configured).
5. The artifact appears on Central after a short delay.

## Troubleshooting

| Message | Cause / fix |
|---|---|
| `no default secret key` / `signing failed: No secret key` | Key not in this keyring. Import it or create a new one (steps 1, 3). |
| `Invalid signature` / `Could not find public key` | Public key not on keyservers yet (step 4). |
| passphrase prompt fails in batch | Set `MAVEN_GPG_PASSPHRASE`. |
| Wrong key used | `-Dgpg.keyname=<KEYID>`. |
| Local build blocked by signing | Drop `-Pdeploy` or add `-Dgpg.skip`. |
