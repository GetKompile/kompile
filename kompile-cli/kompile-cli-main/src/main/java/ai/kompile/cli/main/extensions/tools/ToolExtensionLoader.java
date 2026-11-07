package ai.kompile.cli.main.extensions.tools;

import ai.kompile.cli.main.extensions.InstalledCliExtensions;
import ai.kompile.cli.plugin.api.CliExtension;
import ai.kompile.cli.plugin.api.CliExtensionCatalog;
import ai.kompile.cli.plugin.api.CliExtensionDescriptor;
import ai.kompile.cli.plugin.api.tools.CliToolProvider;
import ai.kompile.cli.plugin.api.tools.ToolProviderContext;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One validated, lazy contribution catalog per host registry. Service discovery belongs to the host. */
public final class ToolExtensionLoader {
    private final CliExtensionCatalog catalog;
    private final ToolExtensionContext context;
    private final Map<String, CliExtension> owners = new LinkedHashMap<>();
    private final Map<CliExtension, Map<String, ToolContribution>> constructed = new LinkedHashMap<>();
    private final Set<String> delivered = new java.util.LinkedHashSet<>();

    public ToolExtensionLoader(Collection<? extends CliExtension> extensions,
                               Set<String> hostCapabilities, ToolExtensionContext context) {
        this(new CliExtensionCatalog(extensions, hostCapabilities), context);
    }

    /** Reuse the host's validated metadata snapshot without rescanning services. */
    public ToolExtensionLoader(CliExtensionCatalog catalog, ToolExtensionContext context) {
        // Validate ALL descriptors (including command-only extensions) before invoking ANY factory.
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.context = Objects.requireNonNull(context, "context");
        for (CliExtension extension : catalog.extensions()) {
            if (extension instanceof CliToolExtension && extension instanceof CliToolProvider) {
                throw new IllegalArgumentException("Ambiguous tool factories for " + catalog.descriptorOf(extension).id());
            }
            if (hasToolFactory(extension)) {
                for (var descriptor : catalog.descriptorOf(extension).tools()) {
                    var missing = catalog.missingCapabilities(descriptor.id(), null);
                    if (!missing.isEmpty()) {
                        throw new IllegalArgumentException("Missing tool capabilities for " + descriptor.id() + ": " + missing);
                    }
                    owners.put(descriptor.id(), extension);
                }
            } else if (!catalog.descriptorOf(extension).tools().isEmpty()) {
                throw new IllegalArgumentException("Extension declares tools without a CliToolExtension or CliToolProvider factory: "
                        + catalog.descriptorOf(extension).id());
            }
        }
    }

    public static ToolExtensionLoader builtins(ToolExtensionContext context) {
        return new ToolExtensionLoader(InstalledCliExtensions.discover(), context);
    }

    public CliExtensionCatalog catalog() {
        return catalog;
    }

    /** A family is constructed only when the host reaches its original registration position. */
    public synchronized ToolContribution contribution(String toolId) {
        CliExtension owner = owners.get(toolId);
        if (owner == null) throw new IllegalArgumentException("No tool extension owns: " + toolId);
        Map<String, ToolContribution> tools = constructed.computeIfAbsent(owner, this::construct);
        ToolContribution contribution = tools.get(toolId);
        if (contribution == null) {
            throw new IllegalArgumentException("Tool unavailable on " + context.surface() + ": " + toolId);
        }
        delivered.add(toolId);
        return contribution;
    }

    public List<ToolContribution> contributions(String... toolIds) {
        return java.util.Arrays.stream(toolIds).map(this::contribution).toList();
    }

    /** Append newly composed tools without shadowing any existing host-owned tool. */
    public synchronized List<ToolContribution> additionalContributions(Set<String> existingToolIds) {
        for (String id : owners.keySet()) {
            if (existingToolIds.contains(id) && !delivered.contains(id)) {
                throw new IllegalArgumentException("Extension tool conflicts with existing host tool: " + id);
            }
        }
        List<ToolContribution> additions = new java.util.ArrayList<>();
        for (CliExtension extension : catalog.extensions()) {
            if (hasToolFactory(extension)) {
                for (ToolContribution contribution : constructed.computeIfAbsent(extension, this::construct).values()) {
                    if (delivered.add(contribution.tool().id())) additions.add(contribution);
                }
            }
        }
        return List.copyOf(additions);
    }

    private static boolean hasToolFactory(CliExtension extension) {
        return extension instanceof CliToolExtension || extension instanceof CliToolProvider;
    }

    private Map<String, ToolContribution> construct(CliExtension extension) {
        Set<String> expected = new java.util.LinkedHashSet<>();
        catalog.descriptorOf(extension).tools().forEach(tool -> expected.add(tool.id()));
        // Never expose the host factory context (or any execution services) to a portable provider.
        ToolProviderContext portable = extension instanceof CliToolProvider
                ? new ToolProviderContext(context.objectMapper(), context.baseUrl(), context.graphBaseUrl(),
                        context.crawlBaseUrl(), context.workingDirectory(),
                        ToolProviderContext.Surface.valueOf(context.surface().name()))
                : null;
        Set<String> omitted = Set.copyOf(extension instanceof CliToolProvider provider
                ? provider.omittedTools(portable) : ((CliToolExtension) extension).omittedTools(context));
        if (!expected.containsAll(omitted)) {
            throw new IllegalArgumentException("Omitted tools must be declared by " + catalog.descriptorOf(extension).id());
        }
        expected.removeAll(omitted);
        Map<String, ToolContribution> result = new LinkedHashMap<>();
        List<ToolContribution> contributions = extension instanceof CliToolProvider provider
                ? List.copyOf(provider.createTools(portable)).stream()
                        .map(tool -> new ToolContribution(new PortableExtensionToolAdapter(tool.tool()),
                                tool.chatHighMemoryGuard())).toList()
                : List.copyOf(((CliToolExtension) extension).createTools(context));
        for (ToolContribution contribution : contributions) {
            String id = contribution.tool().id();
            CliExtensionDescriptor owner = catalog.ownerOfTool(id)
                    .orElseThrow(() -> new IllegalArgumentException("Undeclared tool contribution: " + id));
            if (owners.get(id) != extension) {
                throw new IllegalArgumentException("Tool " + id + " belongs to " + owner.id());
            }
            if (result.putIfAbsent(id, contribution) != null) {
                throw new IllegalArgumentException("Duplicate tool contribution: " + id);
            }
        }
        if (!result.keySet().equals(expected)) {
            throw new IllegalArgumentException("Tool factory must supply exactly the declared tools for "
                    + catalog.descriptorOf(extension).id() + " on " + context.surface() + ": " + expected);
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
