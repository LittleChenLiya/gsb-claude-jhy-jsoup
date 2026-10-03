package org.jsoup.safety;

import org.jsoup.helper.Validate;
import org.jspecify.annotations.Nullable;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 Represents a set of allowed hosts for link auditing. Hosts may be specified as exact names
 (e.g. {@code example.com}) or as explicit subdomain rules (e.g. {@code *.example.com}).
 <p>
 Matching is performed by parsing the host into labels and comparing them label-by-label, never by
 string prefix or suffix operations. Host names are matched case-insensitively.
 </p>
 <p>
 Example:
 </p>
 <pre>{@code
 AllowedHosts hosts = AllowedHosts.of("safe.example", "*.trusted.example");
 hosts.isAllowed("safe.example");        // true (exact match)
 hosts.isAllowed("a.trusted.example");   // true (subdomain match)
 hosts.isAllowed("safe.example.evil.test"); // false (not a prefix match)
 hosts.isAllowed("evil.safe.example");   // false (exact rule does not match subdomains)
 }</pre>
 */
public class AllowedHosts {
    private static final String SubdomainPrefix = "*.";

    private final Set<String> exactHosts; // lower-cased exact host names
    private final Set<String[]> subdomainRules; // lower-cased subdomain rule labels (without the "*." prefix)

    private AllowedHosts(Collection<String> hosts) {
        Validate.notNull(hosts);
        this.exactHosts = new LinkedHashSet<>();
        this.subdomainRules = new LinkedHashSet<>();
        for (String host : hosts) {
            Validate.notNull(host);
            String trimmed = host.trim();
            Validate.notEmpty(trimmed, "Host name must not be empty");
            if (trimmed.startsWith(SubdomainPrefix)) {
                String domain = trimmed.substring(SubdomainPrefix.length());
                Validate.notEmpty(domain, "Subdomain rule must have a domain after '*.'");
                subdomainRules.add(splitLabels(domain));
            } else {
                exactHosts.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     Creates an {@code AllowedHosts} from the supplied host rules.
     @param hosts the host rules (exact names or {@code *.domain} subdomain rules)
     @return a new AllowedHosts
     */
    public static AllowedHosts of(String... hosts) {
        Validate.notNull(hosts);
        return new AllowedHosts(Arrays.asList(hosts));
    }

    /**
     Creates an {@code AllowedHosts} from the supplied host rules.
     @param hosts the host rules (exact names or {@code *.domain} subdomain rules)
     @return a new AllowedHosts
     */
    public static AllowedHosts of(Collection<String> hosts) {
        return new AllowedHosts(hosts);
    }

    /**
     Tests if the supplied host is allowed by these rules.
     @param host the host to test (case-insensitive)
     @return true if the host is allowed
     */
    public boolean isAllowed(@Nullable String host) {
        if (host == null || host.isEmpty()) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        if (exactHosts.contains(lower)) return true;
        String[] labels = splitLabels(lower);
        for (String[] rule : subdomainRules) {
            if (labelsEndWith(labels, rule)) return true;
        }
        return false;
    }

    /**
     Tests if the host of the supplied URL is allowed by these rules.
     @param url the URL whose host to test
     @return true if the URL's host is allowed
     */
    public boolean isAllowed(URL url) {
        Validate.notNull(url);
        return isAllowed(url.getHost());
    }

    /**
     Returns an unmodifiable view of the exact host rules.
     @return the exact host rules
     */
    public Set<String> exactHosts() {
        return Collections.unmodifiableSet(exactHosts);
    }

    /**
     Returns an unmodifiable view of the subdomain rules, as label arrays.
     @return the subdomain rules
     */
    public Set<String[]> subdomainRules() {
        return Collections.unmodifiableSet(subdomainRules);
    }

    /**
     Returns the number of rules (exact + subdomain) in this set.
     @return the rule count
     */
    public int size() {
        return exactHosts.size() + subdomainRules.size();
    }

    private static String[] splitLabels(String host) {
        return host.split("\\.");
    }

    /**
     Tests if the host labels end with the rule labels (label-by-label comparison, not string prefix).
     For a subdomain rule {@code *.example.com}, the rule labels are {@code [example, com]}.
     A host {@code a.example.com} has labels {@code [a, example, com]} which end with {@code [example, com]}.
     A host {@code example.com} has labels {@code [example, com]} which end with {@code [example, com]}.
     A host {@code safe.example.evil.test} has labels {@code [safe, example, evil, test]} which do not
     end with {@code [example, com]}.
     */
    private static boolean labelsEndWith(String[] hostLabels, String[] ruleLabels) {
        if (hostLabels.length < ruleLabels.length) return false;
        int offset = hostLabels.length - ruleLabels.length;
        for (int i = 0; i < ruleLabels.length; i++) {
            if (!hostLabels[offset + i].equals(ruleLabels[i])) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        List<String> all = new ArrayList<>(exactHosts);
        for (String[] rule : subdomainRules) {
            all.add(SubdomainPrefix + joinLabels(rule));
        }
        return all.toString();
    }

    private static String joinLabels(String[] labels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(labels[i]);
        }
        return sb.toString();
    }
}
