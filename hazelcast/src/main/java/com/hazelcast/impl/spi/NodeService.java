/*
 * Copyright (c) 2008-2012, Hazel Bilisim Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.impl.spi;

import com.hazelcast.cluster.ClusterImpl;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.Member;
import com.hazelcast.impl.MemberImpl;
import com.hazelcast.impl.Node;
import com.hazelcast.impl.ThreadContext;
import com.hazelcast.impl.partition.PartitionInfo;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.Data;
import com.hazelcast.nio.Packet;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

import static com.hazelcast.impl.ClusterOperation.REMOTE_CALL;
import static com.hazelcast.nio.IOUtil.toData;
import static com.hazelcast.nio.IOUtil.toObject;

public class NodeService {

    private final ConcurrentMap<String, Object> services = new ConcurrentHashMap<String, Object>(10);
    private final ExecutorService executorService; //= Executors.newCachedThreadPool();
    private final Workers nonBlockingWorkers = new Workers("hz.NonBlocking", 1);
    private final Workers blockingWorkers = new Workers("hz.Blocking", 8);
    private final Node node;
    private final ILogger logger;
    final int partitionCount;
    final int maxBackupCount;

    public NodeService(Node node) {
        this.node = node;
        this.executorService = node.executorManager.getThreadPoolExecutor();
        this.logger = node.getLogger(NodeService.class.getName());
        this.partitionCount = node.groupProperties.CONCURRENT_MAP_PARTITION_COUNT.getInteger();
        this.maxBackupCount = MapConfig.MAX_BACKUP_COUNT;
    }

    public Map<Integer, Object> invokeOnAllPartitions(String serviceName, Operation op) throws Exception {
        Map<Member, ArrayList<Integer>> memberPartitions = getMemberPartitions();
        List<Future> responses = new ArrayList<Future>(memberPartitions.size());
        Data data = toData(op);
        for (Map.Entry<Member, ArrayList<Integer>> mp : memberPartitions.entrySet()) {
            Address target = ((MemberImpl) mp.getKey()).getAddress();
            Invocation inv = createSingleInvocation(serviceName, new PartitionIterator(mp.getValue(), data), -1)
                    .setTarget(target).setTryCount(100).setTryPauseMillis(500).build();
            Future future = inv.invoke();
            responses.add(future);
        }
        Map<Integer, Object> partitionResults = new HashMap<Integer, Object>(partitionCount);
        for (Future r : responses) {
            Object result = r.get();
            Map<Integer, Object> partialResult = null;
            if (result instanceof Data) {
                partialResult = (Map<Integer, Object>) toObject((Data) r.get());
            } else {
                partialResult = (Map<Integer, Object>) result;
            }
            System.out.println(partialResult);
            partitionResults.putAll(partialResult);
        }
        List<Integer> failedPartitions = new ArrayList<Integer>(0);
        for (Map.Entry<Integer, Object> partitionResult : partitionResults.entrySet()) {
            int partitionId = partitionResult.getKey();
            Object result = partitionResult.getValue();
            if (result instanceof Exception) {
                System.out.println("result = " + result);
                failedPartitions.add(partitionId);
            }
        }
        Thread.sleep(500);
        System.out.println("TRYING AGAIN...");
        for (Integer failedPartition : failedPartitions) {
            Invocation inv = createSingleInvocation(serviceName, op, failedPartition).build();
            inv.invoke();
            partitionResults.put(failedPartition, inv);
        }
        for (Integer failedPartition : failedPartitions) {
            Future f = (Future) partitionResults.get(failedPartition);
            Object result = f.get();
            System.out.println(failedPartition + " now response " + result);
            partitionResults.put(failedPartition, result);
        }
        return partitionResults;
    }

    private Map<Member, ArrayList<Integer>> getMemberPartitions() {
        Set<Member> members = node.getClusterImpl().getMembers();
        Map<Member, ArrayList<Integer>> memberPartitions = new HashMap<Member, ArrayList<Integer>>(members.size());
        for (int i = 0; i < partitionCount; i++) {
            Member owner = getOwner(i);
            ArrayList<Integer> ownedPartitions = memberPartitions.get(owner);
            if (ownedPartitions == null) {
                ownedPartitions = new ArrayList<Integer>();
                memberPartitions.put(owner, ownedPartitions);
            }
            ownedPartitions.add(i);
        }
        return memberPartitions;
    }

    public SingleInvocationBuilder createSingleInvocation(String serviceName, Operation op, int partitionId) {
        return new SingleInvocationBuilder(NodeService.this, serviceName, op, partitionId);
    }

    void invokeSingle(final SingleInvocation inv) {
        final Address target = inv.getTarget();
        final Operation op = inv.getOperation();
        final int partitionId = inv.getPartitionId();
        final String serviceName = inv.getServiceName();
        final boolean nonBlocking = (op instanceof NonBlockingOperation);
        setOperationContext(op, serviceName, node.getThisAddress(), -1, partitionId)
                .setLocal(true);
        if (target == null) {
            throw new NullPointerException(inv.getOperation() + ": Target is null");
        }
        if (!(op instanceof NonMemberOperation) && getClusterImpl().getMember(target) == null) {
            throw new TargetNotMemberException(target, partitionId, op.getClass().getName(), serviceName);
        }
        if (getThisAddress().equals(target)) {
            final ExecutorService executor = getExecutor(partitionId, nonBlocking);
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        if (partitionId != -1 && !nonBlocking) {
                            PartitionInfo partitionInfo = getPartitionInfo(partitionId);
                            Address owner = partitionInfo.getOwner();
                            if (!getThisAddress().equals(owner)) {
                                throw new WrongTargetException(getThisAddress(), owner, partitionId,
                                        op.getClass().getName(), serviceName);
                            }
                        }
                        inv.run();
                    } catch (Throwable e) {
                        inv.setResult(e);
                    }
                }
            });
        } else {
            final Packet packet = new Packet();
            packet.operation = REMOTE_CALL;
            packet.blockId = partitionId;
            packet.name = serviceName;
            packet.longValue = nonBlocking ? 1 : 0;
            Data valueData = toData(op);
            packet.setValue(valueData);
            TheCall call = new TheCall(target, inv);
            boolean sent = node.concurrentMapManager.registerAndSend(target, packet, call);
            if (!sent) {
                inv.setResult(new IOException("Packet not sent!"));
            }
        }
    }

    public Future runLocally(final int partitionId, final Callable op, final boolean nonBlocking) {
        final ExecutorService executor = getExecutor(partitionId, nonBlocking);
        return executor.submit(new Callable() {
            public Object call() throws Exception {
                Object response = null;
                if (partitionId != -1 && !(op instanceof NonBlockingOperation)) {
                    PartitionInfo partitionInfo = getPartitionInfo(partitionId);
                    Address owner = partitionInfo.getOwner();
                    if (!getThisAddress().equals(owner)) {
                        response = new WrongTargetException(getThisAddress(), owner, partitionId, op.getClass().getName());
                    } else {
                        response = op.call();
                    }
                } else {
                    response = op.call();
                }
                return response;
            }
        });
    }

    public void handleOperation(final Packet packet) {
        final int partitionId = packet.blockId;
        final boolean backup = packet.longValue == 1;
        final Data data = packet.getValueData();
        final long callId = packet.callId;
        final Address caller = packet.conn.getEndPoint();
        final String serviceName = packet.name;
        final Executor executor = getExecutor(partitionId, backup);
        executor.execute(new Runnable() {
            public void run() {
                try {
                    Object response = null;
                    final Operation op = (Operation) toObject(data);
                    setOperationContext(op, serviceName, caller, callId, partitionId).setConnection(packet.conn);
                    try {
                        if (partitionId != -1) {
                            PartitionInfo partitionInfo = getPartitionInfo(partitionId);
                            Address owner = partitionInfo.getOwner();
                            if (!(op instanceof NonBlockingOperation) && !getThisAddress().equals(owner)) {
                                throw new WrongTargetException(getThisAddress(), owner, partitionId,
                                        op.getClass().getName(), serviceName);
                            }
                        }
                        response = op.call();
                    } catch (Exception e) {
                        if (e instanceof RetryableException) {
                            logger.log(Level.WARNING, e.getClass() + ": " + e.getMessage());
                            logger.log(Level.FINEST, e.getMessage(), e);
                        } else {
                            logger.log(Level.SEVERE, e.getMessage(), e);
                        }
                        response = e;
                    }
                    if (!(op instanceof NoReply)) {
                        if (!(response instanceof Operation)) {
                            response = new Response(response);
                        }
                        packet.clearForResponse();
                        packet.blockId = partitionId;
                        packet.callId = callId;
                        packet.longValue = (response instanceof NonBlockingOperation) ? 1 : 0;
                        packet.setValue(toData(response));
                        packet.name = serviceName;
                        node.concurrentMapManager.sendOrReleasePacket(packet, packet.conn);
                    }
                } catch (Exception e) {
                    logger.log(Level.SEVERE, e.getMessage(), e);
                }
            }
        });
    }

    private OperationContext setOperationContext(Operation op, String serviceName, Address caller,
                                                 long callId, int partitionId) {
        OperationContext context = op.getOperationContext();
        context.setNodeService(this)
                .setService(getService(serviceName))
                .setCaller(caller)
                .setCallId(callId)
                .setPartitionId(partitionId);
        return context;
    }

    private ExecutorService getExecutor(int partitionId, boolean nonBlocking) {
        if (partitionId > -1) {
            return (nonBlocking) ? nonBlockingWorkers.getExecutor(partitionId) : blockingWorkers.getExecutor(partitionId);
        } else {
            return executorService;
        }
    }

    class Workers {
        final int threadCount;
        final ExecutorService[] workers;
        private final String threadNamePrefix;

        Workers(String s, int threadCount) {
            threadNamePrefix = s;
            this.threadCount = threadCount;
            workers = new ExecutorService[threadCount];
            for (int i = 0; i < threadCount; i++) {
                String threadName = threadNamePrefix + "_" + (i + 1);
                workers[i] = newSingleThreadExecutorService(threadName);
            }
        }

        public ExecutorService getExecutor(int partitionId) {
            return workers[partitionId % threadCount];
        }

        ExecutorService newSingleThreadExecutorService(final String threadName) {
            return new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>(),
                    new ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            final Thread t = new Thread(node.threadGroup, r, node.getThreadNamePrefix(threadName), 0) {
                                public void run() {
                                    try {
                                        super.run();
                                    } finally {
                                        try {
                                            ThreadContext.shutdown(this);
                                        } catch (Exception e) {
                                            logger.log(Level.WARNING, e.getMessage(), e);
                                        }
                                    }
                                }
                            };
                            t.setContextClassLoader(node.getConfig().getClassLoader());
                            if (t.isDaemon()) {
                                t.setDaemon(false);
                            }
                            if (t.getPriority() != Thread.NORM_PRIORITY) {
                                t.setPriority(Thread.NORM_PRIORITY);
                            }
                            return t;
                        }
                    },
                    new RejectedExecutionHandler() {
                        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                        }
                    }
            );
        }
    }

    public void notifyCall(long callId, Response response) {
        TheCall call = (TheCall) node.concurrentMapManager.removeRemoteCall(callId);
        if (call != null) {
            call.offerResponse(response);
        }
    }

    public void registerService(String serviceName, Object obj) {
        services.put(serviceName, obj);
    }

    public <T> T getService(String serviceName) {
        return (T) services.get(serviceName);
    }

    public <T> Collection<T> getServices(final Class<T> type) {
        final Collection<T> result = new LinkedList<T>();
        for (Object service : services.values()) {
            if (type.isAssignableFrom(service.getClass())) {
                result.add((T) service);
            }
        }
        return result;
    }

    public Node getNode() {
        return node;
    }

    public ClusterImpl getClusterImpl() {
        return node.getClusterImpl();
    }

    public Address getThisAddress() {
        return node.getThisAddress();
    }

    public Member getOwner(int partitionId) {
        return node.concurrentMapManager.getPartitionServiceImpl().getPartition(partitionId).getOwner();
    }

    public final int getPartitionId(Data key) {
        return node.concurrentMapManager.getPartitionId(key);
    }

    public PartitionInfo getPartitionInfo(int partitionId) {
        return node.concurrentMapManager.getPartitionInfo(partitionId);
    }

    public int getPartitionCount() {
        return partitionCount;
    }
}
