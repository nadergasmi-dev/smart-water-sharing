package com.smartwater.blockchain;

/**
 * Un bloc refusé par la validation.
 *
 * @param block  le bloc refusé (jamais ajouté à la chaîne)
 * @param reason la raison, préfixée par la règle (ex. « V4 : signature invalide … »)
 * @param time   heure du refus, en millisecondes epoch
 */
public record RejectedBlock(Block block, String reason, long time) { }
