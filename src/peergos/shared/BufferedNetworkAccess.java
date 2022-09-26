package peergos.shared;

import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.mutable.*;
import peergos.shared.social.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** This will buffer block writes, and mutable pointer updates and commit in bulk
 *
 */
public class BufferedNetworkAccess extends NetworkAccess {

    private final BufferedStorage blockBuffer;
    private final BufferedPointers pointerBuffer;
    private final int bufferSize;
    private final ContentAddressedStorage blocks;
    private boolean safeToCommit = true;

    public BufferedNetworkAccess(BufferedStorage blockBuffer,
                                 BufferedPointers mutableBuffer,
                                 int bufferSize,
                                 CoreNode coreNode,
                                 Account account,
                                 SocialNetwork social,
                                 ContentAddressedStorage dhtClient,
                                 MutablePointers unbufferedMutable,
                                 BatCave batCave,
                                 MutableTree tree,
                                 WriteSynchronizer synchronizer,
                                 InstanceAdmin instanceAdmin,
                                 SpaceUsage spaceUsage,
                                 ServerMessager serverMessager,
                                 Hasher hasher,
                                 List<String> usernames,
                                 boolean isJavascript) {
        super(coreNode, account, social, blockBuffer, batCave, unbufferedMutable, tree, synchronizer, instanceAdmin, spaceUsage,
                serverMessager, hasher, usernames, isJavascript);
        this.blockBuffer = blockBuffer;
        this.pointerBuffer = mutableBuffer;
        this.bufferSize = bufferSize;
        this.blocks = dhtClient;
        synchronizer.setCommitterBuilder(this::buildCommitter);
        synchronizer.setFlusher((o, v) -> commit(o).thenApply(b -> v));
    }

    @Override
    public Committer buildCommitter(Committer c, PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        return (o, w, wd, e, tid) -> blockBuffer.put(o, w.publicKeyHash, new byte[0], wd.serialize(), tid)
                .thenCompose(newHash -> {
                    PointerUpdate update = pointerBuffer.addWrite(w, MaybeMultihash.of(newHash), e.hash, e.sequence);
                    return maybeCommit(o, commitWatcher)
                            .thenApply(x -> new Snapshot(w.publicKeyHash, new CommittedWriterData(MaybeMultihash.of(newHash), wd, update.sequence)));
                });
    }

    public int bufferedSize() {
        return blockBuffer.totalSize();
    }

    public NetworkAccess disableCommits() {
        safeToCommit = false;
        return this;
    }

    public NetworkAccess enableCommits() {
        safeToCommit = true;
        return this;
    }

    @Override
    public NetworkAccess clear() {
        if (!blockBuffer.isEmpty())
            throw new IllegalStateException("Unwritten blocks!");
        NetworkAccess base = super.clear();
        BufferedStorage blockBuffer = this.blockBuffer.clone();
        WriteSynchronizer synchronizer = new WriteSynchronizer(base.mutable, blockBuffer, hasher);
        MutableTree tree = new MutableTreeImpl(base.mutable, blockBuffer, hasher, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, base.coreNode, base.account, base.social, base.dhtClient,
                base.mutable, base.batCave, tree, synchronizer, base.instanceAdmin, base.spaceUsage, base.serverMessager, hasher, usernames, isJavascript());
    }

    @Override
    public NetworkAccess withStorage(Function<ContentAddressedStorage, ContentAddressedStorage> modifiedStorage) {
        BufferedStorage blockBuffer = this.blockBuffer.withStorage(modifiedStorage);
        WriteSynchronizer synchronizer = new WriteSynchronizer(super.mutable, blockBuffer, hasher);
        MutableTree tree = new MutableTreeImpl(mutable, blockBuffer, hasher, synchronizer);
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, coreNode, account, social, blocks,
                mutable, batCave, tree, synchronizer, instanceAdmin, spaceUsage, serverMessager, hasher, usernames, isJavascript());
    }

    public NetworkAccess withCorenode(CoreNode newCore) {
        return new BufferedNetworkAccess(blockBuffer, pointerBuffer, bufferSize, newCore, account, social, dhtClient,
                mutable, batCave, tree, synchronizer, instanceAdmin, spaceUsage, serverMessager, hasher, usernames, isJavascript());
    }

    public boolean isFull() {
        return bufferedSize() >= bufferSize;
    }

    private CompletableFuture<Boolean> maybeCommit(PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        if (safeToCommit && isFull())
            return commit(owner, commitWatcher);
        return Futures.of(true);
    }

    @Override
    public synchronized CompletableFuture<Boolean> commit(PublicKeyHash owner, Supplier<Boolean> commitWatcher) {
        List<BufferedPointers.WriterUpdate> writerUpdates = pointerBuffer.getUpdates();
        if (blockBuffer.isEmpty() && writerUpdates.isEmpty())
            return Futures.of(true);
        // Condense pointers and do a mini GC to remove superfluous work
        List<Cid> roots = pointerBuffer.getRoots();
        if (roots.isEmpty())
            throw new IllegalStateException("Where are the pointers?");
        blockBuffer.gc(roots);
        Map<PublicKeyHash, SigningPrivateKeyAndPublicHash> writers = pointerBuffer.getSigners();
        List<Pair<BufferedPointers.WriterUpdate, Optional<CommittedWriterData>>> writes = blockBuffer.getAllWriterData(writerUpdates);
        return blockBuffer.signBlocks(writers)
                .thenCompose(b -> blocks.startTransaction(owner))
                .thenCompose(tid -> Futures.combineAllInOrder(writes.stream()
                                .map(u -> blockBuffer.commit(owner, u.left.writer, tid)
                                        .thenCompose(b -> pointerBuffer.commit(owner, writers.get(u.left.writer),
                                                        new PointerUpdate(u.left.prevHash, u.left.currentHash, u.left.currentSequence))
                                                .thenCompose(x -> u.right
                                                        .map(cwd -> synchronizer.updateWriterState(owner, u.left.writer, new Snapshot(u.left.writer, cwd)))
                                                        .orElse(Futures.of(true)))))
                                .collect(Collectors.toList()))
                        .thenCompose(x -> blocks.closeTransaction(owner, tid))
                        .thenApply(x -> {
                            pointerBuffer.clear();
                            return commitWatcher.get();
                        }));
    }

    @Override
    public String toString() {
        return "Blocks(" + blockBuffer.size() + "),Pointers(" + pointerBuffer.getUpdates().size()+")";
    }
}
