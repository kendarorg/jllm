package org.kendar.llm.harness.settings;

import java.util.List;

/**
 * @param fetchMaxBytes        largest response body {@code web_fetch} reads; null or {@code <= 0} = default (5 MB)
 * @param blockLocalHost       refuse loopback, link-local, site-local and unique-local addresses in web_fetch and
 *                             web_download, redirects included; null = true (default), {@code false} allows them (local services)
 * @param blockExtraAddresses  more IPs, CIDRs, host names or {@code *.suffix} to refuse; null = none (default)
 */
public record WebLimits(Long fetchMaxBytes, Boolean blockLocalHost, List<String> blockExtraAddresses) {
    public WebLimits {
        blockExtraAddresses = blockExtraAddresses == null ? List.of() : List.copyOf(blockExtraAddresses);
    }
}
