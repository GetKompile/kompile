package ai.kompile.e2e.fixtures;

import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stub document retriever that returns configurable docs
 * and records received queries for assertion.
 */
public class StubDocumentRetriever implements DocumentRetriever {

    private List<RetrievedDoc> documentsToReturn = new ArrayList<>();
    private final List<String> receivedQueries = new ArrayList<>();

    public void setDocumentsToReturn(List<RetrievedDoc> docs) {
        this.documentsToReturn = new ArrayList<>(docs);
    }

    public List<String> getReceivedQueries() {
        return Collections.unmodifiableList(receivedQueries);
    }

    public void clearHistory() {
        receivedQueries.clear();
    }

    @Override
    public List<String> retrieve(String query, int maxResults) {
        receivedQueries.add(query);
        return documentsToReturn.stream()
                .limit(maxResults)
                .map(RetrievedDoc::getText)
                .toList();
    }

    @Override
    public List<RetrievedDoc> retrieveWithDetails(String query, int maxResults) {
        receivedQueries.add(query);
        return documentsToReturn.stream()
                .limit(maxResults)
                .toList();
    }
}
