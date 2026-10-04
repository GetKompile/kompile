/* Copyright 2026 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.main.auth.source;

import ai.kompile.cli.main.auth.CredentialStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;

@Command(name = "erp", mixinStandardHelpOptions = true,
        description = "Configure local named ERP credentials (bare command opens the wizard).",
        subcommands = {ErpAuthCommand.ListConnections.class, ErpAuthCommand.Status.class, ErpAuthCommand.Remove.class})
public final class ErpAuthCommand implements Callable<Integer> {
    @Option(names = "--name", description = "Connection name referenced by crawl properties.connectionName.") String name;
    @Option(names = "--source-type", description = "SAP_NETWEAVER, ODATA, DYNAMICS365 (F&O), NETSUITE, ODOO (19 JSON-2), SALESFORCE, ORACLE_FUSION, ORACLE_EBS, JD_EDWARDS, INFOR_MONGOOSE (REST v2 through ION), ACUMATICA (contract-based REST).") String type;
    @Option(names = "--service-root", description = "HTTPS service URL, without query or credentials.") String root;
    @Option(names = "--tenant", description = "Tenant identity for binding; ODOO: optional X-Odoo-Database; INFOR_MONGOOSE: required X-Infor-MongooseConfig.") String tenant;
    @Option(names = "--username", description = "Basic-auth username for SAP/ODATA/Fusion/EBS/JDE; omit for bearer credentials.") String username;
    @Option(names = "--secret-from-env", paramLabel = "ENV") String environment;
    @Option(names = "--secret-file", paramLabel = "PATH") Path file;
    @Option(names = "--secret-stdin") boolean stdin;
    @Option(names = "--expires-at", description = "Bearer expiry, epoch milliseconds; 0 means externally managed expiry.") long expiresAt;
    @CommandLine.Spec CommandLine.Model.CommandSpec spec;
    @Override public Integer call() {
        try {
            String secret;
            int inputs = (environment == null ? 0 : 1) + (file == null ? 0 : 1) + (stdin ? 1 : 0);
            boolean bare = name == null && type == null && root == null && username == null && tenant == null && inputs == 0 && expiresAt == 0;
            if (bare) {
                var console = System.console();
                if (console == null) throw new IllegalArgumentException("No interactive console. Use --name --source-type --service-root and --secret-from-env, --secret-file or --secret-stdin.");
                type = console.readLine("Source type (%s): ", String.join(", ", ai.kompile.source.erp.ErpSourceConfiguration.TYPES.stream().sorted().toList()));
                name = console.readLine("Connection name: ");
                root = console.readLine("HTTPS service root: ");
                tenant = console.readLine("Tenant identity (required Mongoose configuration for INFOR_MONGOOSE; otherwise optional): ");
                username = console.readLine("Basic username (required for SAP/EBS; optional for ODATA/Fusion/JDE, blank for bearer): ");
                char[] password = console.readPassword("Password or bearer token: ");
                if (password == null) throw new IllegalArgumentException("ERP setup cancelled");
                secret = new String(password);
                Arrays.fill(password, '\0');
            } else {
                if (inputs != 1) throw new IllegalArgumentException("Use exactly one secret input source");
                if (environment != null) secret = System.getenv(environment);
                else if (file != null) secret = Files.readString(file, StandardCharsets.UTF_8).replaceFirst("[\\r\\n]+$", "");
                else secret = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            }
            if (name == null || name.isBlank() || type == null || root == null)
                throw new IllegalArgumentException("ERP setup requires name, source-type and service-root");
            ErpConnectionCredentials.save(CredentialStore.create(), type.trim(), name.trim(), root, tenant, username, secret, expiresAt);
            spec.commandLine().getOut().println("Saved named ERP connection. Use properties.connectionName with entitySet in crawl_documents.");
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("ERP setup failed. Check required options, service URL and secret input; no credential was displayed.");
            if (e instanceof IllegalArgumentException) spec.commandLine().getErr().println(e.getMessage());
            return 2;
        }
    }
    @Command(name = "list", mixinStandardHelpOptions = true, description = "List locally stored ERP connection names (no secrets).")
    public static final class ListConnections implements Callable<Integer> {
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public Integer call() throws Exception {
            for (String type : ai.kompile.source.erp.ErpSourceConfiguration.TYPES.stream().sorted().toList())
                for (var info : CredentialStore.create().list(ErpConnectionCredentials.provider(type)))
                    spec.commandLine().getOut().println(type + " " + info.credentialName());
            return 0;
        }
    }
    @Command(name = "status", mixinStandardHelpOptions = true, description = "Show service binding and credential expiry, never secrets.")
    public static final class Status implements Callable<Integer> {
        @Option(names = "--source-type", required = true) String type;
        @Option(names = "--name", required = true) String name;
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;
        @Override public Integer call() throws Exception {
            spec.commandLine().getOut().println(ErpConnectionCredentials.status(CredentialStore.create(), type, name));
            return 0;
        }
    }
    @Command(name = "remove", mixinStandardHelpOptions = true, description = "Remove one named ERP credential.")
    public static final class Remove implements Callable<Integer> {
        @Option(names = "--source-type", required = true) String type;
        @Option(names = "--name", required = true) String name;
        @Option(names = "--yes", required = true) boolean yes;
        @Override public Integer call() throws Exception {
            return yes && CredentialStore.create().deleteCredential(ErpConnectionCredentials.provider(type), name) ? 0 : 2;
        }
    }
}
