package com.smartwater.security;

import com.smartwater.blockchain.Block;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

// Signature numérique des blocs (ECDSA, courbe P-256, contrat section 6.2)
public final class BlockSigner {

    private static final String ALGO = "SHA256withECDSA";

    private BlockSigner() { }

    // Signe le hash du bloc (chaîne de 64 caractères hexa, UTF-8) ; résultat en Base64
    public static String sign(String hash, PrivateKey privateKey) {
        try {
            Signature s = Signature.getInstance(ALGO);
            s.initSign(privateKey);
            s.update(hash.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Échec de la signature", e);
        }
    }

    // Vérifie que le bloc a bien été signé par le propriétaire de la clé publique
    public static boolean verify(Block b, PublicKey publicKey) {
        if (b == null || b.getSignature() == null || b.getHash() == null || publicKey == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ALGO);
            s.initVerify(publicKey);
            s.update(b.getHash().getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(b.getSignature()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;   // signature mal formée = invalide
        }
    }
}
