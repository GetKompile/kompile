/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.ocr.structured;

import lombok.Getter;

/**
 * Types of semantic fields that can be extracted from documents.
 */
@Getter
public enum FieldType {
    // Personal information
    NAME("Person name"),
    FIRST_NAME("First name"),
    LAST_NAME("Last name"),
    FULL_NAME("Full name"),

    // Contact information
    EMAIL("Email address"),
    PHONE("Phone number"),
    ADDRESS("Physical address"),
    CITY("City"),
    STATE("State/Province"),
    POSTAL_CODE("Postal/ZIP code"),
    COUNTRY("Country"),

    // Identity
    SSN("Social Security Number"),
    ID_NUMBER("ID number"),
    PASSPORT_NUMBER("Passport number"),
    DRIVERS_LICENSE("Driver's license number"),

    // Dates
    DATE("General date"),
    BIRTH_DATE("Date of birth"),
    ISSUE_DATE("Issue date"),
    EXPIRY_DATE("Expiration date"),
    DUE_DATE("Due date"),

    // Financial
    AMOUNT("Monetary amount"),
    CURRENCY("Currency"),
    TOTAL("Total amount"),
    SUBTOTAL("Subtotal"),
    TAX("Tax amount"),
    DISCOUNT("Discount"),
    ACCOUNT_NUMBER("Account number"),
    INVOICE_NUMBER("Invoice number"),

    // Document metadata
    TITLE("Document title"),
    DOCUMENT_NUMBER("Document number"),
    REFERENCE_NUMBER("Reference number"),

    // Organization
    COMPANY_NAME("Company name"),
    DEPARTMENT("Department"),
    JOB_TITLE("Job title"),

    // Other
    SIGNATURE("Signature"),
    CHECKBOX("Checkbox field"),
    CUSTOM("Custom field type"),
    UNKNOWN("Unknown field type");

    private final String description;

    FieldType(String description) {
        this.description = description;
    }

    /**
     * Checks if this field type requires validation.
     */
    public boolean requiresValidation() {
        return switch (this) {
            case EMAIL, PHONE, SSN, DATE, BIRTH_DATE, ISSUE_DATE, EXPIRY_DATE,
                 DUE_DATE, AMOUNT, POSTAL_CODE -> true;
            default -> false;
        };
    }

    /**
     * Checks if this field type contains sensitive information.
     */
    public boolean isSensitive() {
        return switch (this) {
            case SSN, ID_NUMBER, PASSPORT_NUMBER, DRIVERS_LICENSE,
                 ACCOUNT_NUMBER, BIRTH_DATE -> true;
            default -> false;
        };
    }
}
