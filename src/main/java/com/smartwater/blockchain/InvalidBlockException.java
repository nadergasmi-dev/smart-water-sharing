package com.smartwater.blockchain;

// Levée quand un bloc est refusé. getMessage() donne la raison, préfixée par la règle (ex
public class InvalidBlockException extends Exception {

    private final String rule;

    public InvalidBlockException(String rule, String detail) {
        super(rule + " : " + detail);
        this.rule = rule;
    }

    // Code de la règle violée (« V1 » à « V7 »)
    public String getRule() {
        return rule;
    }
}
