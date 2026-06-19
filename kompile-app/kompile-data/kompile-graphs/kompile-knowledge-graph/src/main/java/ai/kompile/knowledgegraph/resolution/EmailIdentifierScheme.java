/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.resolution;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@link IdentifierScheme} for email addresses — a minimal second kind that demonstrates the
 * framework is genuinely generic (not barcode-only). An email is a global identifier that resolves
 * to a person/organization entity; canonicalization is trim + lower-case + a basic shape check.
 */
@Component
public class EmailIdentifierScheme implements IdentifierScheme {

    private static final Set<String> KEYS = Set.of(
            "email", "email_address", "emailaddress", "e_mail", "mail", "contact_email");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Override
    public String kind() {
        return "EMAIL";
    }

    @Override
    public boolean handlesKey(String metadataKey) {
        return metadataKey != null && KEYS.contains(metadataKey.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public Optional<String> canonicalize(String rawValue) {
        if (rawValue == null) {
            return Optional.empty();
        }
        String value = rawValue.trim().toLowerCase(Locale.ROOT);
        return EMAIL.matcher(value).matches() ? Optional.of(value) : Optional.empty();
    }
}
