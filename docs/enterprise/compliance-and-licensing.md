# Compliance and licensing

## License

[Apache License 2.0](../../LICENSE). Commercial use, modification and redistribution are allowed. Redistributions and
derivative works must keep the copyright notice and the [NOTICE](../../NOTICE) file. There is no copyleft obligation
on an application that embeds the library.

## Dependency footprint

The harness core is deliberately small: Jackson, the JDK `HttpClient`, jsoup, json-schema-validator, swagger-parser (with its transitive libraries) and `slf4j-api` (full list: `mvn dependency:tree -pl core/jllm-harness`). No
Spring, JPA or Micrometer in the library. Plugins bring their own dependencies only when you install them (`jllm-pdf`,
`jllm-docs`: document readers; `jllm-workflows`). `jllm-web` adds Spring Boot.

### Not bundled: GraalJS

The `run_js` tool (`jllm-js`) needs GraalJS, which is not in any jllm jar: `jllm-cli`, `jllm-web` and the plugin declare
it `provided`, and the release zip ships it in a replaceable `lib-ext/` folder together with a manifest (coordinate and
SHA-256 per jar). The release scripts re-read the licence of every resolved pom, fail on anything but UPL, MIT, Apache,
BSD or Unicode/ICU (the ICU4J shaded in GraalJS), and fail if a front-end jar contains GraalJS classes. Version caveat:
from GraalJS 25.1 the central `js` pom declares UPL and MIT and `js-community` is a deprecated alias; earlier `js` poms
pulled an enterprise artifact under Oracle terms. At the pinned version the non-community isolate (`js-isolate`) still
declares the GraalVM Free Terms and Conditions: the shipped set uses `js-isolate-community`, and whoever installs the
`oracle` set (`fetch-graaljs oracle`) accepts those terms. Who accepts the Oracle terms is the operator. Details:
[js guide](../guides/js.md#licensing), [NOTICE](../../NOTICE).

Generate the authoritative list for your build and feed it to your scanner:

```sh
mvn -pl core/jllm-harness dependency:tree
```

## Data handling

| Data | Where it goes |
|---|---|
| Prompts, tool results and file contents the agent reads | to the model provider you configure |
| Chat history | your store (files under `.jllm/history` by default) |
| OAuth tokens | your `TokenStore` |
| Web users and roles | your files or repositories |

Use a local OpenAI-compatible endpoint if data must not leave your network. Provider terms, retention and regions are
the provider's own; check them before choosing one.

## Supply chain

Releases go to Maven Central under the verified namespace `org.kendar.llm`, with sources and javadoc jars and GPG
signatures ([PUBLISHING](../reference/publishing.md)). The web application is not published as an artifact.

## Not provided

Certifications (SOC 2, ISO 27001), a support contract, an SLA and a security audit report are not provided by the
project. The [security model](security-model.md) states what the code does and does not guarantee.
