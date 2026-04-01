package ai.kompile.serving.openai.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorResponse {

    private ErrorDetail error;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ErrorDetail {
        private String message;
        private String type;
        private String param;
        private String code;
    }

    public static ErrorResponse of(String message, String type, String code) {
        return ErrorResponse.builder()
                .error(ErrorDetail.builder()
                        .message(message)
                        .type(type)
                        .code(code)
                        .build())
                .build();
    }
}
