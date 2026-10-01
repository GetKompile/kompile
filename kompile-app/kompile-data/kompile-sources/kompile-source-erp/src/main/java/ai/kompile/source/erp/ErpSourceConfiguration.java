/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.source.erp;

import ai.kompile.core.loaders.DocumentSourceDescriptor;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fixed read-only ERP profiles; never accepts Camel URIs or route scripts. */
public record ErpSourceConfiguration(String type, URI root, String entitySet, Map<String, Object> properties,
                                     int maxRecords, int pageSize, int maxPages, int timeoutMillis, int maxResponseBytes) {
    private static final Set<String> ALLOWED = Set.of("serviceRoot", "entitySet", "connectionName", "tenant", "username",
            "password", "accessToken", "filter", "select", "orderBy", "keyFields", "maxRecords", "pageSize", "maxPages",
            "timeoutMillis", "maxResponseBytes", "sapClient", "authMode");
    public static boolean isErp(String type) {
        return type != null && TYPES.contains(type.toUpperCase(Locale.ROOT));
    }
    public static final Set<String> TYPES = Set.of("SAP_NETWEAVER", "ODATA", "DYNAMICS365", "NETSUITE", "ODOO", "SALESFORCE");
    public static boolean requiresBearer(String type) {
        return isErp(type) && !Set.of("SAP_NETWEAVER", "ODATA").contains(type.toUpperCase(Locale.ROOT));
    }
    public static void validateProfileRoot(String type, URI root) {
        String path = root.getPath();
        boolean valid = switch (type.toUpperCase(Locale.ROOT)) {
            case "DYNAMICS365" -> path.endsWith("/data/");
            case "NETSUITE" -> path.endsWith("/services/rest/record/v1/");
            case "ODOO" -> path.endsWith("/json/2/");
            case "SALESFORCE" -> path.matches(".*/services/data/v[0-9]+\\.[0-9]+/");
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException("ERP serviceRoot does not match the selected API profile");
    }
    public static ErpSourceConfiguration from(DocumentSourceDescriptor descriptor) {
        Map<String, Object> p = descriptor.getMetadata() == null ? Map.of() : descriptor.getMetadata();
        for (String key : p.keySet()) if (!ALLOWED.contains(key))
            throw new IllegalArgumentException("Unsupported ERP property: " + key);
        String type = descriptor.getType().name();
        if (!isErp(type)) throw new IllegalArgumentException("Unsupported ERP source type");
        URI root = serviceRoot(text(p, "serviceRoot", descriptor.getPathOrUrl()));
        validateProfileRoot(type, root);
        if (descriptor.getPathOrUrl() != null && !descriptor.getPathOrUrl().isBlank()
                && !root.equals(serviceRoot(descriptor.getPathOrUrl())))
            throw new IllegalArgumentException("ERP locator does not match serviceRoot");
        String entity = text(p, "entitySet", "");
        if (!entity.matches(type.equals("ODOO") ? "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*" : "[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("ERP entitySet must be one collection identifier, not a path or action");
        String sapClient = text(p, "sapClient", "");
        if (!sapClient.isEmpty() && (!"SAP_NETWEAVER".equals(type) || !sapClient.matches("[0-9]{3}")))
            throw new IllegalArgumentException("sapClient requires SAP_NETWEAVER and a three-digit client");
        String token = text(p, "accessToken", "");
        String user = text(p, "username", "");
        String password = text(p, "password", "");
        if (requiresBearer(type) && (token.isBlank() || !user.isEmpty() || !password.isEmpty()))
            throw new IllegalArgumentException("This ERP profile requires a bearer credential");
        String tenant = text(p, "tenant", "");
        if (tenant.contains("\r") || tenant.contains("\n")) throw new IllegalArgumentException("Invalid ERP tenant");
        if (Set.of("NETSUITE", "ODOO", "SALESFORCE").contains(type)) {
            for (String key : new String[]{"filter", "orderBy"}) if (!text(p, key, "").isEmpty())
                throw new IllegalArgumentException("This ERP profile does not accept filter or orderBy");
            String select = text(p, "select", "");
            if (type.equals("NETSUITE") && !select.isEmpty()) throw new IllegalArgumentException("NetSuite record reads do not accept select");
            if (!type.equals("NETSUITE") && !select.matches("[A-Za-z_][A-Za-z0-9_]*(\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*)*"))
                throw new IllegalArgumentException("Odoo/Salesforce require select as comma-separated field identifiers");
        }
        if ((!token.isEmpty() && (!user.isEmpty() || !password.isEmpty())) || (user.isEmpty() != password.isEmpty()))
            throw new IllegalArgumentException("ERP requires either bearer or complete basic credentials, not both");
        if (token.contains("\r") || token.contains("\n") || user.contains(":") || user.contains("\r") || user.contains("\n"))
            throw new IllegalArgumentException("Invalid ERP authentication value");
        String mode = text(p, "authMode", "");
        if (!mode.isEmpty() && !("basic".equalsIgnoreCase(mode) && !user.isEmpty())
                && !("bearer".equalsIgnoreCase(mode) && !token.isEmpty()))
            throw new IllegalArgumentException("ERP authMode does not match credentials");
        int records = number(p, "maxRecords", 100, 100_000);
        return new ErpSourceConfiguration(type, root, entity, Map.copyOf(p), records,
                Math.min(records, number(p, "pageSize", 100, type.equals("NETSUITE") ? 1000 : 10_000)), number(p, "maxPages", 20, 1000),
                number(p, "timeoutMillis", 30_000, 120_000), number(p, "maxResponseBytes", 4_194_304, 16_777_216));
    }
    /** HTTPS is mandatory except literal loopback addresses for local fixture/dev services. */
    public static URI serviceRoot(String value) {
        try {
            URI u = URI.create(value == null ? "" : value.trim());
            String host = u.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
            if (host == null || !("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()) && loopback)
                    || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
                    || u.getRawPath().contains("%") || !u.normalize().equals(u))
                throw new IllegalArgumentException();
            String path = u.getPath().replaceAll("/+$", "") + "/";
            return new URI(u.getScheme().toLowerCase(Locale.ROOT), null, host.toLowerCase(Locale.ROOT), u.getPort(), path, null, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("ERP serviceRoot must be an HTTPS service URL without credentials, query or fragment");
        }
    }
    /** Validate every continuation before credentials are sent; only the same collection may be read. */
    public URI continuation(URI current, String next) {
        try {
            URI resolved = current.resolve(next);
            if (!resolved.normalize().equals(resolved)) throw new IllegalArgumentException();
            if (type.equals("SALESFORCE")) {
                if (!root.getScheme().equals(resolved.getScheme()) || !root.getHost().equals(resolved.getHost())
                        || root.getPort() != resolved.getPort() || resolved.getRawUserInfo() != null
                        || resolved.getRawQuery() != null || resolved.getRawFragment() != null
                        || !resolved.getRawPath().startsWith(root.getRawPath() + "query/")
                        || !resolved.getRawPath().substring((root.getRawPath() + "query/").length()).matches("[A-Za-z0-9_-]+"))
                    throw new IllegalArgumentException();
                return resolved;
            }
            URI collection = root.resolve(entitySet);
            if (!resolved.getScheme().equals(collection.getScheme()) || !resolved.getHost().equals(collection.getHost())
                    || resolved.getPort() != collection.getPort() || resolved.getRawUserInfo() != null
                    || resolved.getRawFragment() != null || !resolved.getRawPath().equals(collection.getRawPath()))
                throw new IllegalArgumentException();
            Map<String, String> initial = queryParameters(firstPage());
            Map<String, String> query = queryParameters(resolved);
            for (String key : query.keySet()) {
                if (!Set.of("$top", "$skip", "$skiptoken", "$filter", "$select", "$orderby", "$format", "sap-client").contains(key))
                    throw new IllegalArgumentException();
                if (!Set.of("$top", "$skip", "$skiptoken").contains(key)
                        && !query.get(key).equals(initial.get(key))) throw new IllegalArgumentException();
            }
            if (query.containsKey("$top")) {
                int top = Integer.parseInt(query.get("$top"));
                if (top < 1 || top > pageSize) throw new IllegalArgumentException();
            }
            // Servers may omit scope parameters from nextLink; retain the caller's read scope.
            StringBuilder result = new StringBuilder(resolved.toString());
            for (var entry : initial.entrySet()) if (!query.containsKey(entry.getKey())) {
                result.append(result.indexOf("?") < 0 ? '?' : '&')
                        .append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            }
            return URI.create(result.toString());
        } catch (Exception e) { throw new IllegalArgumentException("ERP continuation must remain within the configured entity collection"); }
    }
    private static Map<String, String> queryParameters(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
        }
        return values;
    }
    public URI firstPage() {
        if (type.equals("NETSUITE")) return recordPage(0);
        if (type.equals("ODOO")) return root.resolve(entitySet + "/search_read");
        if (type.equals("SALESFORCE")) {
            String fields = String.join(",", selectedFields("Id"));
            String query = "SELECT " + fields + " FROM " + entitySet + " ORDER BY Id LIMIT " + maxRecords;
            return URI.create(root.resolve("query") + "?q=" + encode(query));
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("$top", Integer.toString(pageSize));
        if (type.equals("SAP_NETWEAVER")) query.put("$format", "json");
        for (String key : new String[]{"filter", "select", "orderBy"}) {
            String value = text(properties, key, "");
            if (!value.isEmpty()) query.put("$" + key.toLowerCase(Locale.ROOT), value);
        }
        String client = text(properties, "sapClient", "");
        if (!client.isEmpty()) query.put("sap-client", client);
        String encoded = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        return URI.create(root.resolve(entitySet) + "?" + encoded);
    }
    public URI recordPage(int offset) {
        return URI.create(root.resolve(entitySet) + "?limit=" + pageSize + "&offset=" + offset);
    }
    public java.util.List<String> selectedFields(String id) {
        var fields = new java.util.LinkedHashSet<String>();
        fields.add(id);
        for (String field : text(properties, "select", "").split(",")) if (!field.isBlank()) fields.add(field.trim());
        return java.util.List.copyOf(fields);
    }
    public static String scopeIdentity(String type, String locator, Map<String, Object> p) {
        StringBuilder result = new StringBuilder(type.toLowerCase(Locale.ROOT));
        append(result, serviceRoot(text(p, "serviceRoot", locator)).toString());
        for (String key : new String[]{"tenant", "sapClient", "connectionName", "username", "entitySet", "filter", "select", "orderBy", "keyFields"})
            append(result, text(p, key, ""));
        return result.toString();
    }
    private static void append(StringBuilder s, String value) { s.append('|').append(value.length()).append(':').append(value); }
    public static void applyLimit(Map<String, Object> p, int maximum) {
        int existing = number(p, "maxRecords", Math.min(100, maximum), 100_000);
        p.put("maxRecords", Math.min(existing, maximum));
    }
    public static String text(Map<String, Object> p, String key, String fallback) {
        Object value = p.get(key);
        return value == null ? fallback : Set.of("password", "accessToken").contains(key)
                ? value.toString() : value.toString().trim();
    }
    private static int number(Map<String, Object> p, String key, int fallback, int ceiling) {
        try {
            int value = p.containsKey(key) ? Integer.parseInt(p.get(key).toString()) : fallback;
            if (value < 1 || value > ceiling) throw new NumberFormatException();
            return value;
        } catch (Exception e) { throw new IllegalArgumentException("ERP " + key + " must be between 1 and " + ceiling); }
    }
    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
    @Override public String toString() { return "ErpSourceConfiguration[type=" + type + ", credentials=<redacted>]"; }
}
