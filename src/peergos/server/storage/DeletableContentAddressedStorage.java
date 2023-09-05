package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** This interface is only used locally on a server and never exposed.
 *  These methods allow garbage collection and local mirroring to be implemented.
 *
 */
public interface DeletableContentAddressedStorage extends ContentAddressedStorage {

    ForkJoinPool usagePool = new ForkJoinPool(100);

    Stream<Cid> getAllBlockHashes();

    Stream<Pair<Cid, String>> getAllBlockHashVersions();

    List<Multihash> getOpenTransactionBlocks();

    void clearOldTransactions(long cutoffMillis);

    boolean hasBlock(Cid hash);

    void delete(Cid block);

    default void delete(Pair<Cid, String> blockVersion) {
        delete(blockVersion.left);
    }

    default void bloomAdd(Multihash hash) {}

    default Optional<BlockMetadataStore> getBlockMetadataStore() {
        return Optional.empty();
    }

    default void bulkDelete(List<Pair<Cid, String>> blockVersions) {
        for (Pair<Cid, String> version : blockVersions) {
            delete(version);
        }
    }

    /**
     *
     * @param hash
     * @return The data with the requested hash, deserialized into cbor, or Optional.empty() if no object can be found
     */
    CompletableFuture<Optional<CborObject>> get(Cid hash, String auth);

    default CompletableFuture<Optional<CborObject>> get(Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        if (bat.isEmpty())
            return get(hash, "");
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> get(hash, auth));
    }

    /**
     * Get a block of data that is not in ipld cbor format, just raw bytes
     * @param hash
     * @return
     */
    CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth);

    default CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth, boolean doAuth) {
        return getRaw(hash, auth);
    }

    default CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h) {
        return getRaw(hash, bat, ourId, h, true);
    }

    default CompletableFuture<Optional<byte[]>> getRaw(Cid hash, Optional<BatWithId> bat, Cid ourId, Hasher h, boolean doAuth) {
        if (bat.isEmpty())
            return getRaw(hash, "");
        return bat.get().bat.generateAuth(hash, ourId, 300, S3Request.currentDatetime(), bat.get().id, h)
                .thenApply(BlockAuth::encode)
                .thenCompose(auth -> getRaw(hash, auth, doAuth));
    }

    /** Ensure that local copies of all blocks in merkle tree referenced are present locally
     *
     * @param owner
     * @param existing
     * @param updated
     * @return
     */
    default CompletableFuture<List<Cid>> mirror(PublicKeyHash owner,
                                                Optional<Cid> existing,
                                                Optional<Cid> updated,
                                                Optional<BatWithId> mirrorBat,
                                                Cid ourNodeId,
                                                TransactionId tid,
                                                Hasher hasher) {
        if (updated.isEmpty())
            return Futures.of(Collections.emptyList());
        Cid newRoot = updated.get();
        if (existing.equals(updated))
            return Futures.of(Collections.singletonList(newRoot));
        boolean isRaw = newRoot.isRaw();

        Optional<byte[]> newVal = RetryStorage.runWithRetry(3, () -> getRaw(newRoot, mirrorBat, ourNodeId, hasher, false)).join();
        if (newVal.isEmpty())
            throw new IllegalStateException("Couldn't retrieve block: " + newRoot);
        if (isRaw)
            return Futures.of(Collections.singletonList(newRoot));

        CborObject newBlock = CborObject.fromByteArray(newVal.get());
        List<Multihash> newLinks = newBlock.links().stream()
                .filter(h -> !h.isIdentity())
                .collect(Collectors.toList());
        List<Multihash> existingLinks = existing.map(h -> get(h, mirrorBat, ourNodeId, hasher).join())
                .flatMap(copt -> copt.map(CborObject::links).map(links -> links.stream()
                        .filter(h -> !h.isIdentity())
                        .collect(Collectors.toList())))
                .orElse(Collections.emptyList());

        for (int i=0; i < newLinks.size(); i++) {
            Optional<Cid> existingLink = i < existingLinks.size() ?
                    Optional.of((Cid)existingLinks.get(i)) :
                    Optional.empty();
            Optional<Cid> updatedLink = Optional.of((Cid)newLinks.get(i));
            mirror(owner, existingLink, updatedLink, mirrorBat, ourNodeId, tid, hasher).join();
        }
        return Futures.of(Collections.singletonList(newRoot));
    }

    /**
     * Get all the merkle-links referenced directly from this object
     * @param root The hash of the object whose links we want
     * @return A list of the multihashes referenced with ipld links in this object
     */
    default CompletableFuture<List<Cid>> getLinks(Cid root, String auth) {
        if (root.isRaw())
            return CompletableFuture.completedFuture(Collections.emptyList());
        return get(root, auth).thenApply(opt -> opt
                .map(cbor -> cbor.links().stream().map(c -> (Cid) c).collect(Collectors.toList()))
                .orElse(Collections.emptyList())
        );
    }

    default CompletableFuture<Long> getRecursiveBlockSize(Cid block) {
        return getLinks(block, "").thenCompose(links -> {
            List<CompletableFuture<Long>> subtrees = links.stream()
                    .filter(m -> ! m.isIdentity())
                    .map(c -> Futures.runAsync(() -> getRecursiveBlockSize(c)))
                    .collect(Collectors.toList());
            return getSize(block)
                    .thenCompose(sizeOpt -> {
                        CompletableFuture<Long> reduced = Futures.reduceAll(subtrees,
                                0L, (t, fut) -> fut.thenApply(x -> x + t), (a, b) -> a + b);
                        return reduced.thenApply(sum -> sum + sizeOpt.orElse(0));
                    });
        });
    }

    default CompletableFuture<Long> getChangeInContainedSize(Optional<Cid> original, Cid updated) {
        if (! original.isPresent())
            return getRecursiveBlockSize(updated);
        return getChangeInContainedSize(original.get(), updated);
    }

    default CompletableFuture<Pair<Integer, List<Cid>>> getLinksAndSize(Cid block, String auth) {
        return getLinks(block, auth)
                .thenCompose(links -> getSize(block).thenApply(size -> new Pair<>(size.orElse(0), links)));
    }

    default CompletableFuture<Long> getChangeInContainedSize(Cid original, Cid updated) {
        return getLinksAndSize(original, "")
                .thenCompose(before -> getLinksAndSize(updated, "").thenCompose(after -> {
                    int objectDelta = after.left - before.left;
                    List<Cid> beforeLinks = before.right.stream().filter(c -> !c.isIdentity()).collect(Collectors.toList());
                    List<Cid> onlyBefore = new ArrayList<>(beforeLinks);
                    onlyBefore.removeAll(after.right);
                    List<Cid> afterLinks = after.right.stream().filter(c -> !c.isIdentity()).collect(Collectors.toList());
                    List<Cid> onlyAfter = new ArrayList<>(afterLinks);
                    onlyAfter.removeAll(before.right);

                    int nPairs = Math.min(onlyBefore.size(), onlyAfter.size());
                    List<Pair<Cid, Cid>> pairs = IntStream.range(0, nPairs)
                            .mapToObj(i -> new Pair<>(onlyBefore.get(i), onlyAfter.get(i)))
                            .collect(Collectors.toList());

                    List<Cid> extraBefore = onlyBefore.subList(nPairs, onlyBefore.size());
                    List<Cid> extraAfter = onlyAfter.subList(nPairs, onlyAfter.size());

                    CompletableFuture<Long> beforeRes = Futures.runAsync(() -> getAllRecursiveSizes(extraBefore), usagePool);
                    CompletableFuture<Long> afterRes = Futures.runAsync(() -> getAllRecursiveSizes(extraAfter), usagePool);
                    CompletableFuture<Long> pairsRes = Futures.runAsync(() -> getSizeDiff(pairs), usagePool);
                    return beforeRes.thenCompose(priorSize -> afterRes.thenApply(postSize -> postSize - priorSize + objectDelta))
                            .thenCompose(total -> pairsRes.thenApply(res -> res + total));
                }));
    }

    private CompletableFuture<Long> getAllRecursiveSizes(List<Cid> roots) {
        List<CompletableFuture<Long>> allSizes = roots.stream()
                .map(c -> Futures.runAsync(() -> getRecursiveBlockSize(c), usagePool))
                .collect(Collectors.toList());
        return Futures.reduceAll(allSizes,
                0L,
                (s, f) -> f.thenApply(size -> size + s),
                (a, b) -> a + b);
    }

    private CompletableFuture<Long> getSizeDiff(List<Pair<Cid, Cid>> pairs) {
        List<CompletableFuture<Long>> pairDiffs = pairs.stream()
                .map(p -> Futures.runAsync(() -> getChangeInContainedSize(p.left, p.right), usagePool))
                .collect(Collectors.toList());
        return Futures.reduceAll(pairDiffs,
                0L,
                (s, f) -> f.thenApply(size -> size + s),
                (a, b) -> a + b);
    }

    class HTTP extends ContentAddressedStorage.HTTP implements DeletableContentAddressedStorage {

        private final HttpPoster poster;

        public HTTP(HttpPoster poster, boolean isPeergosServer, Hasher hasher) {
            super(poster, isPeergosServer, hasher);
            this.poster = poster;
        }

        @Override
        public Stream<Cid> getAllBlockHashes() {
            String jsonStream = new String(poster.get(apiPrefix + REFS_LOCAL).join());
            return JSONParser.parseStream(jsonStream).stream()
                    .map(m -> (String) (((Map) m).get("Ref")))
                    .map(Cid::decode);
        }

        @Override
        public Stream<Pair<Cid, String>> getAllBlockHashVersions() {
            return getAllBlockHashes().map(c -> new Pair<>(c, null));
        }

        @Override
        public void delete(Cid hash) {
            poster.get(apiPrefix + BLOCK_RM + "?stream-channels=true&arg=" + hash.toString()).join();
        }

        @Override
        public void bloomAdd(Multihash hash) {
            poster.get(apiPrefix + BLOOM_ADD + "?stream-channels=true&arg=" + hash.toString()).join();
        }

        @Override
        public boolean hasBlock(Cid hash) {
            return poster.get(apiPrefix + BLOCK_PRESENT + "?stream-channels=true&arg=" + hash.toString())
                    .thenApply(raw -> new String(raw).equals("true")).join();
        }

        @Override
        public List<Multihash> getOpenTransactionBlocks() {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public void clearOldTransactions(long cutoffMillis) {
            throw new IllegalStateException("Unimplemented!");
        }

        @Override
        public CompletableFuture<Optional<CborObject>> get(Cid hash, String auth) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(CborObject.fromByteArray(hash.getHash())));
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash + "&auth=" + auth)
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(CborObject.fromByteArray(raw)));
        }

        @Override
        public CompletableFuture<Optional<byte[]>> getRaw(Cid hash, String auth) {
            if (hash.isIdentity())
                return CompletableFuture.completedFuture(Optional.of(hash.getHash()));
            return poster.get(apiPrefix + BLOCK_GET + "?stream-channels=true&arg=" + hash + "&auth=" + auth)
                    .thenApply(raw -> raw.length == 0 ? Optional.empty() : Optional.of(raw));
        }
    }
}
