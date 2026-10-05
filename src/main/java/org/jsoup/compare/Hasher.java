package org.jsoup.compare;

/**
 A sink that incrementally absorbs canonical token data and produces a hex digest. Implementations must be
 deterministic: the same sequence of {@link #update(String)} calls must always yield the same {@link #digestHex()}.

 <p>The default implementation is {@link Sha256Hasher}. A custom implementation (for example a constant or
 collision-prone hasher) may be injected via {@link CompareConfig.Builder#hasherFactory(java.util.function.Supplier)};
 note that {@link SubtreeComparer} never trusts the digest alone and always verifies equivalence along the canonical
 token stream, so a weak hasher can only cause extra verification work, never a false positive.</p>
 */
public interface Hasher {
    /** Absorb a chunk of canonical data. */
    void update(String data);

    /** @return the digest of all data absorbed so far, as a lowercase hex string. */
    String digestHex();
}
