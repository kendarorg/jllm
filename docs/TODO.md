Add litellm provider

The clean version:

OIDC for identity. People sign in to the JLLM web app with your company OIDC provider, which jllm-web already supports.
LiteLLM for model access. On a user's first login, create a LiteLLM virtual key for them, with user_id set to their OIDC subject and their budget and rate limits attached. Store it encrypted as that user's model credential. Only LiteLLM holds the real provider keys.
One audit trail. Spend and usage per person show up in LiteLLM under the same identity as in JLLM.

What I'd avoid is doing both: per-person provider credentials and LiteLLM in between. Then LiteLLM has to pass each person's provider credentials through, which loses most of the point of having it (central keys, budgets, one place to revoke). If you specifically want provider-side identity, as with Bedrock or Vertex through cloud IAM federation, that can replace LiteLLM rather than sit behind it.

This is to caluclate cost prices limit. Now with litellm then add 




=============

Docker containers for web chats: the right move, and the biggest missing piece

The web release template configures no sandbox, so web chats run shell commands with the local backend: directly on the host, with no isolation. Running each chat in its own container fixes that, and the Docker defaults are already good (--network none, read-only filesystem, all capabilities dropped, non-root user, resource limits).

For a multi-user server, it also needs:

Keep the network off, or filter it. The SSRF guard only covers web_fetch and web_download. If you give containers network access, curl in bash bypasses it completely and can reach 169.254.169.254 and your internal network. Either keep network: none, or attach containers to a network whose outbound traffic is filtered.
Treat the Docker socket as root. Whatever can start containers is root-equivalent on that host, so a compromised JVM means a compromised host. Use rootless Docker, a hardened runtime such as gVisor (--runtime=runsc), or run the containers on a separate host. Remote mode would do that, but the remote daemon still has no TLS, as covered above.
No fallback to running without isolation. localFallback defaults to true. It asks first, but in the web app it asks the chat user, who will just click yes. For web, make it false in the server settings so that no Docker means no shell, not a host shell.
Container lifecycle: one container and volume per chat, cleaned up when the chat is deleted or idle, with disk quotas. That's what your fork adds, and it's what I'd review first.
Pin the image by digest, not just python:3.12-slim, so it can't change underneath you.



1. SSRF: DNS rebinding and missing address ranges

DNS rebinding. AddressPolicy.check resolves the host name and checks the addresses, then HttpClient resolves the same name again to connect. An attacker who controls a domain's DNS can answer the first lookup with a public address and the second with 127.0.0.1 or 169.254.169.254, using a time-to-live of zero so nothing is cached. The guard approves a safe address, and the connection goes to the internal one.

There's a second, simpler hole in the same method: if the guard's lookup fails, check returns ("the request fails by itself"). An attacker's DNS server can refuse the first query and answer the second, and nothing is checked at all. It should fail closed: unresolvable means refused.

How to close it, best option first:

Filter at resolution time, not before it. Since JDK 18, Java lets you plug in your own name resolver (InetAddressResolverProvider, JEP 418). A resolver that drops internal addresses means the check and the connection use the same answer, so there's no window. It applies to the whole JVM, so it needs a flag per thread or per client to apply only to web_fetch and web_download, not to model clients or MCP servers on the internal network.
Egress proxy. Route the web tools through a proxy that enforces the block itself, such as Squid or Envoy. This is the usual production answer: it also covers MCP and REST calls, and it lives outside the app.
Connect by IP. Resolve once, then connect to the IP while keeping the original host name for TLS. The JDK HttpClient makes this awkward, because Host is a restricted header and the TLS host name comes from the URI, so I wouldn't start here.

Missing ranges. isLocal covers loopback, link-local, site-local, any-local and fc00::/7. Gaps:

100.64.0.0/10 (carrier-grade NAT): Tailscale and some cloud internal networks use it. Alibaba Cloud's metadata service is at 100.100.100.200.
0.0.0.0/8: on Linux and macOS, connecting to 0.0.0.0 reaches local services. Java's isAnyLocalAddress only matches exactly 0.0.0.0, not the rest of the range.
IPv6 forms that embed an IPv4 address: NAT64 (64:ff9b::/96) and 6to4 (2002::/16) can carry 169.254.169.254 or 127.0.0.1 inside an IPv6 address. Extract the embedded IPv4 address and check it.
Smaller ones: 198.18.0.0/15, 192.0.0.0/24, 240.0.0.0/4, and multicast. The broadcast address is not covered either.
2. The remote daemon's plain-HTTP default

I was too generous last round. The Java client supports https://, but the daemon itself (sandbox-agent.py) only speaks plain HTTP. Its own comment says so in capitals, and calls the real fix "not done." HTTPS only works if you put a TLS-terminating proxy (nginx, Caddy, stunnel) in front of the daemon yourself.

Why it matters:

In remote mode the container port is published on 0.0.0.0 of the remote Docker host, so anything that can reach that host can reach the daemon.
The token is fixed for the container's lifetime and sent in clear on every request. One captured request is enough to replay it and run any command in the sandbox. The token comparison itself is done right (constant-time hmac.compare_digest).
allowInsecure defaults to true, so this happens with only one warning per host in the log.

The fix, as the daemon's own comment describes:

Have the daemon serve TLS itself, with a certificate and key passed in as JLLM_TLS_CERT / JLLM_TLS_KEY, loaded into Python's ssl.SSLContext.
Optionally require client certificates (mutual TLS), so a stolen token alone isn't enough.
Then default allowInsecure to false for any host that isn't loopback.

Until then, document remote mode as "SSH tunnel or trusted network only."