package com.smartwater.blockchain;

/**
 * Levée quand un bloc est refusé. {@code getMessage()} donne la raison, préfixée par la règle
 * (ex. {@code "V6 : quota dépassé pour C (250 L demandés, 200 L restants)"}), pour les logs et le dashboard.
 */
public class InvalidBlockException extends Exception {

    private final String rule;

    /** @param rule   code de la règle violée : « V1 » à « V7 » (contrat, section 6.3) */
    public InvalidBlockException(String rule, String detail) {
        super(rule + " : " + detail);
        this.rule = rule;
    }

    /** Code de la règle violée (« V1 » à « V7 »). */
    public String getRule() {
        return rule;
    }
}
