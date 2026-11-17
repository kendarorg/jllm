# Compliance and licensing

## License

[Apache License 2.0](../../LICENSE). Commercial use, modification and redistribution are allowed. Redistributions and
derivative works must keep the copyright notice and the [NOTICE](../../NOTICE) file. There is no copyleft obligation
on an application that embeds the library.

## Dependency footprint

The harness core is deliberately small: Jackson, the JDK `HttpClient`, jsoup, swagger-parser and `slf4j-api`. No
Spring, JPA or Micrometer in the library. Plugins bring their own dependencies only when you install them (`jllm-js`:
GraalJS; `jllm-pdf`, `jllm-docs`: document readers; `jllm-workflows`). `jllm-web` adds Spring Boot.

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
