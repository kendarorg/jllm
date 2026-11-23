## Publishing to Maven Central

Same flow as the-protocol-master. Namespace `org.kendar.llm` is verified on https://central.sonatype.com.

### One-off setup
* GPG key (RSA 4096, same email as the Central account), public key on a keyserver:
  `gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`
* Central Portal token in `~/.m2/settings.xml`:
```xml
<servers>
  <server>
    <id>central</id>
    <username>TOKEN_USER</username>
    <password>TOKEN_PASSWORD</password>
  </server>
</servers>
```

### Release
1. Set `<revision>` in the root `pom.xml` (no SNAPSHOT).
2. Verify (sources, javadoc, flatten, signatures):
   `JAVA_HOME=~/.jdks/corretto-21.0.9 mvn clean verify -P deploy`
3. Publish:
   `JAVA_HOME=~/.jdks/corretto-21.0.9 mvn clean deploy -P deploy`
4. Release the validated deployment from the Central Portal (Deployments tab) if not auto-published.
5. Tag: `git tag v<revision> && git push --tags`

### Notes
* The `deploy` profile adds source, javadoc, flatten, GPG and central-publishing plugins.
* `jllm-web` (Spring Boot app), `jllm-cli`, `core/jllm-eval` and `jacoco` are excluded via `maven.deploy.skip` + `central-publish.skip`.
* Exclude another module by setting the same two properties in its pom.
* Moving GPG keys between machines: `gpg --export-secret-keys --armor`, `gpg --import`; delete the exports afterwards.
