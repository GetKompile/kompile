package ai.kompile.project.server.xet;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code GET /v1/reconstructions/{fileId}} — the Xet download recipe plus
 * fetch instructions. Field names match the Xet protocol exactly.
 *
 * @see <a href="https://huggingface.co/docs/xet/download-protocol">Xet Download Protocol</a>
 */
public class ReconstructionResponse {

    @JsonProperty("offset_into_first_range")
    public long offsetIntoFirstRange;

    @JsonProperty("terms")
    public List<Term> terms = new ArrayList<>();

    @JsonProperty("fetch_info")
    public Map<String, List<FetchInfo>> fetchInfo = new LinkedHashMap<>();

    public static class Term {
        @JsonProperty("hash")
        public String hash;
        @JsonProperty("unpacked_length")
        public long unpackedLength;
        @JsonProperty("range")
        public Range range;

        public Term() {
        }

        public Term(String hash, long unpackedLength, Range range) {
            this.hash = hash;
            this.unpackedLength = unpackedLength;
            this.range = range;
        }
    }

    public static class FetchInfo {
        @JsonProperty("range")
        public Range range;
        @JsonProperty("url")
        public String url;
        @JsonProperty("url_range")
        public Range urlRange;

        public FetchInfo() {
        }

        public FetchInfo(Range range, String url, Range urlRange) {
            this.range = range;
            this.url = url;
            this.urlRange = urlRange;
        }
    }

    /** {start,end}. Chunk-index ranges are end-exclusive; byte ranges are end-inclusive. */
    public static class Range {
        @JsonProperty("start")
        public long start;
        @JsonProperty("end")
        public long end;

        public Range() {
        }

        public Range(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }
}
