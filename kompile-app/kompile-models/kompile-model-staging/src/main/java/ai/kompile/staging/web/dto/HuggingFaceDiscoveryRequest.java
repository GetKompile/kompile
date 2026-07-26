/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Discovery request. The bearer token is body-only and never forms part of a
 * persisted URL or server log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HuggingFaceDiscoveryRequest {
    private String reference;
    private String revision;
    private String authToken;
}
