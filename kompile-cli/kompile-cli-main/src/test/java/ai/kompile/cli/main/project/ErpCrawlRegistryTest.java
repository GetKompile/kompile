package ai.kompile.cli.main.project;

import ai.kompile.cli.main.auth.source.SourceCredentialResolver;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ErpCrawlRegistryTest {
    @TempDir Path dir;
    @Test void scopeIdentityDistinguishesAccountEntityAndQueryButNotSecretsOrLimits() {
        var p=new LinkedHashMap<String,Object>(Map.of("entitySet","Orders","connectionName","reader","filter","active eq true"));
        String key=LocalExternalSourceLoaderRegistry.identityKey("ODATA","https://erp.example/service",p);
        var changed=new LinkedHashMap<>(p); changed.put("accessToken","secret");changed.put("maxRecords",2);
        assertEquals(key,LocalExternalSourceLoaderRegistry.identityKey("ODATA","https://erp.example/service/",changed));
        for(String field:List.of("entitySet","connectionName","filter","tenant","select","sapClient","keyFields")) {
            var other=new LinkedHashMap<>(p);other.put(field,"different");
            assertNotEquals(key,LocalExternalSourceLoaderRegistry.identityKey("ODATA","https://erp.example/service",other));
        }
        assertTrue(LocalExternalSourceLoaderRegistry.sourceTypes().containsAll(Set.of("ODATA","SAP_NETWEAVER")));
    }
    @Test void materializationBoundsRecordsAndPreservesSnapshotOnFailureOrEmptyRead() throws Exception {
        Path target=dir.resolve("snapshot"); var p=Map.<String,Object>of("entitySet","Orders","maxRecords",200);
        List<DocumentSourceDescriptor> seen=new ArrayList<>();
        DocumentLoader good=new DocumentLoader() {
            public String getName(){return "fake-erp";}
            public boolean supports(DocumentSourceDescriptor d){return true;}
            public List<Document> load(DocumentSourceDescriptor d){seen.add(d);return List.of(new Document("ERP order",Map.of("source_path","erp:1")));}
        };
        var resolver=SourceCredentialResolver.create();var mapper=new ObjectMapper();
        var result=LocalExternalSourceLoaderRegistry.materialize("ODATA","https://erp.example/service/",p,target,2,mapper,resolver,good);
        assertEquals(2,seen.get(0).getMetadata().get("maxRecords"));
        assertEquals("https://erp.example/service/",seen.get(0).getMetadata().get("serviceRoot"));
        assertFalse(result.incremental());
        String original=Files.readString(result.files().get(0));
        DocumentLoader bad=new DocumentLoader() {
            public String getName(){return "fake-erp";}
            public boolean supports(DocumentSourceDescriptor d){return true;}
            public List<Document> load(DocumentSourceDescriptor d)throws Exception{throw new IOException("page budget exhausted");}
        };
        assertThrows(IOException.class,()->LocalExternalSourceLoaderRegistry.materialize("ODATA","https://erp.example/service/",p,target,2,mapper,resolver,bad));
        assertEquals(original,Files.readString(result.files().get(0)));
        DocumentLoader empty=new DocumentLoader() {
            public String getName(){return "fake-erp";}
            public boolean supports(DocumentSourceDescriptor d){return true;}
            public List<Document> load(DocumentSourceDescriptor d){return List.of();}
        };
        assertThrows(IllegalStateException.class,()->LocalExternalSourceLoaderRegistry.materialize("ODATA","https://erp.example/service/",p,target,2,mapper,resolver,empty));
        assertEquals(original,Files.readString(result.files().get(0)));
        try(var paths=Files.list(dir)){assertFalse(paths.anyMatch(path->path.getFileName().toString().contains(".staging-")));}
    }
}
