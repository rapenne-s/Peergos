package peergos.server.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class RequestCountingStorage extends DelegatingStorage {

    private final ContentAddressedStorage target;
    public AtomicInteger get = new AtomicInteger(0);
    public AtomicInteger put = new AtomicInteger(0);
    public AtomicInteger getRaw = new AtomicInteger(0);
    public AtomicInteger putRaw = new AtomicInteger(0);
    public AtomicInteger start = new AtomicInteger(0);
    public AtomicInteger close = new AtomicInteger(0);
    public AtomicInteger champGet = new AtomicInteger(0);

    public RequestCountingStorage(ContentAddressedStorage target) {
        super(target);
        this.target = target;
    }

    public void reset() {
        get.set(0);
        put.set(0);
        getRaw.set(0);
        putRaw.set(0);
        start.set(0);
        close.set(0);
        champGet.set(0);
    }

    public int requestTotal() {
        return get.get() + put.get() + getRaw.get() + putRaw.get() + start.get() + close.get() + champGet.get();
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(PublicKeyHash owner) {
        return target.startTransaction(owner).thenApply(res -> {
            start.incrementAndGet();
            return res;
        });
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(PublicKeyHash owner, TransactionId tid) {
        return target.closeTransaction(owner, tid).thenApply(res -> {
            close.incrementAndGet();
            return res;
        });
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return target.directToOrigin();
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(PublicKeyHash owner, Multihash root, byte[] champKey) {
        return target.getChampLookup(owner, root, champKey)
                .thenApply(blocks -> {
                    champGet.incrementAndGet();
                    return blocks;
                });
    }

    @Override
    public CompletableFuture<List<Multihash>> put(PublicKeyHash owner,
                                                  PublicKeyHash writer,
                                                  List<byte[]> signedHashes,
                                                  List<byte[]> blocks,
                                                  TransactionId tid) {
        return target.put(owner, writer, signedHashes, blocks, tid)
                .thenApply(res -> {
                    put.incrementAndGet();
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash key, String auth) {
        return target.get(key, auth).thenApply(cborOpt -> {
            get.incrementAndGet();
            return cborOpt;
        });
    }

    @Override
    public CompletableFuture<List<Multihash>> putRaw(PublicKeyHash owner,
                                                     PublicKeyHash writer,
                                                     List<byte[]> signatures,
                                                     List<byte[]> blocks,
                                                     TransactionId tid,
                                                     ProgressConsumer<Long> progressConsumer) {
        return target.putRaw(owner, writer, signatures, blocks, tid, progressConsumer)
                .thenApply(res -> {
                    putRaw.incrementAndGet();
                    return res;
                });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash key, String auth) {
        return target.getRaw(key, auth).thenApply(rawOpt -> {
            getRaw.incrementAndGet();
            return rawOpt;
        });
    }
}
