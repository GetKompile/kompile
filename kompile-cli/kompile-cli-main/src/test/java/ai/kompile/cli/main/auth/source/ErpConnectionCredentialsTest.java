package ai.kompile.cli.main.auth.source;

import ai.kompile.cli.main.auth.CredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ErpConnectionCredentialsTest {
    @TempDir Path dir;
    CredentialStore store() { return new CredentialStore(dir.resolve("auth.json")); }
    @Test void namedSecretOnlyResolvesForBoundServiceTenantAndIdentity() throws Exception {
        var store=store();
        ErpConnectionCredentials.save(store,"ODATA","finance","https://erp.example/service","tenant-a",null,"secret-token",0);
        var props=new LinkedHashMap<String,Object>(Map.of("connectionName","finance","serviceRoot","https://erp.example/service/","tenant","tenant-a"));
        ErpConnectionCredentials.resolve(store,"ODATA",props);
        assertEquals("secret-token",props.get("accessToken"));
        assertEquals("bearer",props.get("authMode"));
        assertFalse(ErpConnectionCredentials.status(store,"ODATA","finance").toString().contains("secret-token"));
        assertFalse(store.list("erp-odata").toString().contains("secret-token"));
        List<Map<String,Object>> overrides=List.of(Map.of("serviceRoot","https://other.example/service"),Map.of("tenant","tenant-b"),
                Map.of("username","other"),Map.of("authMode","basic"),Map.of("password","override"),Map.of("fromChannelConnection","channel"));
        for (var extra:overrides) {
            var rejected=new LinkedHashMap<String,Object>(Map.of("connectionName","finance")); rejected.putAll(extra);
            var e=assertThrows(IllegalArgumentException.class,()->ErpConnectionCredentials.resolve(store,"ODATA",rejected));
            assertFalse(e.toString().contains("secret-token")); assertFalse(rejected.containsKey("accessToken"));
        }
    }
    @Test void noNamedReferenceNeverUsesDefaultOAuthCredentials() throws Exception {
        var store=store();
        ErpConnectionCredentials.save(store,"ODATA","default","https://erp.example/",null,null,"secret-token",0);
        var props=new LinkedHashMap<String,Object>();
        ErpConnectionCredentials.resolve(store,"ODATA",props); assertTrue(props.isEmpty());
        assertThrows(Exception.class,()->ErpConnectionCredentials.resolve(store,"SAP_NETWEAVER",new LinkedHashMap<>(Map.of("connectionName","default"))));
    }
    @Test void expiredEnvelopeCannotBeUsedAndBasicWhitespaceSurvives() throws Exception {
        var store=store();
        ErpConnectionCredentials.save(store,"SAP_NETWEAVER","sap","https://sap.example/gateway/",null,"reader"," pass ",0);
        var props=new LinkedHashMap<String,Object>(Map.of("connectionName","sap"));
        ErpConnectionCredentials.resolve(store,"SAP_NETWEAVER",props); assertEquals(" pass ",props.get("password"));
        assertThrows(IllegalArgumentException.class,()->ErpConnectionCredentials.save(store,"ODATA","old","https://erp.example/",null,null,"secret",1));
        assertThrows(IllegalArgumentException.class,()->ErpConnectionCredentials.save(store,"SAP_NETWEAVER","bad","https://sap.example/",null,null,"secret",0));
        store.put("erp-odata","expired",ai.kompile.cli.common.auth.ManagedCredential.apiKey(
                "{\"version\":1,\"serviceRoot\":\"https://erp.example/\",\"authMode\":\"bearer\",\"accessToken\":\"secret\",\"expiresAt\":1}"),false);
        assertThrows(IllegalArgumentException.class,()->ErpConnectionCredentials.resolve(store,"ODATA",new LinkedHashMap<>(Map.of("connectionName","expired"))));
    }
    @Test void vendorConnectionsAreBearerOnlyAndIsolatedByProfileAndRoot() throws Exception {
        var roots = Map.of("DYNAMICS365", "https://erp.example/data/",
                "NETSUITE", "https://erp.example/services/rest/record/v1/",
                "ODOO", "https://erp.example/json/2/", "SALESFORCE", "https://erp.example/services/data/v60.0/");
        var store = store();
        for (var entry : roots.entrySet()) {
            String type = entry.getKey(), root = entry.getValue();
            ErpConnectionCredentials.save(store, type, "reader", root, "finance", null, "private-token", 0);
            var properties = new LinkedHashMap<String, Object>(Map.of("connectionName", "reader",
                    "serviceRoot", root, "tenant", "finance"));
            ErpConnectionCredentials.resolve(store, type, properties);
            assertEquals("private-token", properties.get("accessToken"));
            assertEquals("bearer", properties.get("authMode"));
            assertFalse(ErpConnectionCredentials.status(store, type, "reader").toString().contains("private-token"));
            assertEquals(java.util.Set.of("password", "accessToken"), SourceCredentialResolver.sourceSecretFields(type));
            assertThrows(IllegalArgumentException.class, () -> ErpConnectionCredentials.save(
                    store, type, "basic", root, null, "reader", "password", 0));
            assertThrows(IllegalArgumentException.class, () -> ErpConnectionCredentials.save(
                    store, type, "wrong-root", "https://erp.example/", null, null, "private-token", 0));
            assertThrows(IllegalArgumentException.class, () -> ErpConnectionCredentials.resolve(store, type,
                    new LinkedHashMap<>(Map.of("connectionName", "reader", "serviceRoot", "https://other.example/"))));
        }
        assertThrows(java.io.IOException.class, () -> ErpConnectionCredentials.resolve(store, "ODATA",
                new LinkedHashMap<>(Map.of("connectionName", "reader"))));
    }
    @Test void authCommandRegisteredAndNoLiteralSecretOption() {
        var source=new CommandLine(new AuthSourceCommand());
        var erp=source.getSubcommands().get("erp"); assertNotNull(erp);
        assertTrue(erp.getSubcommands().keySet().containsAll(java.util.Set.of("list","status","remove")));
        assertNotNull(erp.getCommandSpec().findOption("--secret-stdin"));
        assertNull(erp.getCommandSpec().findOption("--password")); assertNull(erp.getCommandSpec().findOption("--access-token"));
    }
}
