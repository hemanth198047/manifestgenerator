package com.example.casc.wizard;

import java.util.List;
import java.util.Locale;

/**
 * Name-based hints that a column or field MAY hold personal or sensitive data. A hint is only a prompt to review:
 * it never sets or clears an attestation, a PII element or a data subject category.
 */
public final class PiiHints {
    private PiiHints() {}

    private static final List<String> PATTERNS = List.of("email", "e_mail", "ssn", "social_security", "national_id", "nationalid",
            "passport", "tax_id", "taxid", "phone", "mobile", "birth", "dob", "first_name", "last_name", "surname", "full_name",
            "firstname", "lastname", "fullname", "address", "iban", "account_no", "account_number", "accountnumber", "acct_num",
            "card_number", "cardnumber", "salary", "gender", "ip_address");

    /** Returns a short reason, or null when the name gives no hint. */
    public static String hint(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT).replace("-", "_");
        for (String p : PATTERNS) {
            if (n.contains(p)) {
                return "name resembles '" + p + "'";
            }
        }
        return null;
    }
}
