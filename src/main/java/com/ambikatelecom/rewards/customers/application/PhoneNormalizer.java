package com.ambikatelecom.rewards.customers.application;

import com.ambikatelecom.rewards.web.ApiException;
import org.springframework.http.HttpStatus;

/** Indian mobile numbers to E.164 (+91XXXXXXXXXX). */
public final class PhoneNormalizer {

    private PhoneNormalizer() {}

    public static String toE164(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        else if (digits.length() == 11 && digits.startsWith("0")) digits = digits.substring(1);
        if (!digits.matches("[6-9]\\d{9}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE",
                    "Enter a valid 10-digit mobile number.", "phone");
        }
        return "+91" + digits;
    }
}
