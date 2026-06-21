package ai.kompile.project.server.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Small JSON response bodies for the Xet Hub-token and CAS upload endpoints. */
public final class XetResponses {

    private XetResponses() {
    }

    public static class XetTokenResponse {
        public String accessToken;
        public long exp;
        public String casUrl;

        public XetTokenResponse() {
        }

        public XetTokenResponse(String accessToken, long exp, String casUrl) {
            this.accessToken = accessToken;
            this.exp = exp;
            this.casUrl = casUrl;
        }
    }

    public static class UploadXorbResponse {
        @JsonProperty("was_inserted")
        public boolean wasInserted;

        public UploadXorbResponse() {
        }

        public UploadXorbResponse(boolean wasInserted) {
            this.wasInserted = wasInserted;
        }
    }

    public static class UploadShardResponse {
        @JsonProperty("result")
        public int result;

        public UploadShardResponse() {
        }

        public UploadShardResponse(int result) {
            this.result = result;
        }
    }
}
