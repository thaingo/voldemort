/*
 * Copyright 2008-2012 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.server.protocol.admin;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import voldemort.client.protocol.pb.ProtoUtils;
import voldemort.client.protocol.pb.VAdminProto;
import voldemort.client.protocol.pb.VAdminProto.FetchPartitionEntriesRequest;
import voldemort.server.StoreRepository;
import voldemort.server.VoldemortConfig;
import voldemort.store.ErrorCodeMapper;
import voldemort.store.metadata.MetadataStore;
import voldemort.store.stats.StreamStats;
import voldemort.store.stats.StreamStats.Operation;
import voldemort.utils.ByteArray;
import voldemort.utils.ClosableIterator;
import voldemort.utils.NetworkClassLoader;
import voldemort.utils.Pair;
import voldemort.utils.RebalanceUtils;
import voldemort.utils.Time;
import voldemort.versioning.Versioned;

import com.google.protobuf.Message;

/**
 * Fetches the entries using an efficient partition scan
 * 
 */
public class FetchPartitionEntriesStreamRequestHandler extends FetchStreamRequestHandler {

    protected Set<Integer> fetchedPartitions;
    protected ClosableIterator<Pair<ByteArray, Versioned<byte[]>>> entriesPartitionIterator;
    protected List<Integer> replicaTypeList;
    protected List<Integer> partitionList;
    protected Integer currentIndex;

    public FetchPartitionEntriesStreamRequestHandler(FetchPartitionEntriesRequest request,
                                                     MetadataStore metadataStore,
                                                     ErrorCodeMapper errorCodeMapper,
                                                     VoldemortConfig voldemortConfig,
                                                     StoreRepository storeRepository,
                                                     NetworkClassLoader networkClassLoader,
                                                     StreamStats stats) {
        super(request,
              metadataStore,
              errorCodeMapper,
              voldemortConfig,
              storeRepository,
              networkClassLoader,
              stats,
              Operation.FETCH_ENTRIES);
        logger.info("Starting fetch entries for store '" + storageEngine.getName()
                    + "' with replica to partition mapping " + replicaToPartitionList);
        fetchedPartitions = new HashSet<Integer>();
        replicaTypeList = new ArrayList<Integer>();
        partitionList = new ArrayList<Integer>();
        currentIndex = 0;
        entriesPartitionIterator = null;

        // flatten the replicatype to partition map
        for(Integer replicaType: replicaToPartitionList.keySet()) {
            if(replicaToPartitionList.get(replicaType) != null) {
                for(Integer partitionId: replicaToPartitionList.get(replicaType)) {
                    partitionList.add(partitionId);
                    replicaTypeList.add(replicaType);
                }
            }
        }
    }

    public StreamRequestHandlerState handleRequest(DataInputStream inputStream,
                                                   DataOutputStream outputStream)
            throws IOException {

        // process the next partition
        if(entriesPartitionIterator == null) {
            // we are finally done
            if(currentIndex == partitionList.size()) {
                stats.closeHandle(handle);
                return StreamRequestHandlerState.COMPLETE;
            }

            boolean found = false;
            // find the next partition to scan
            while(!found && (currentIndex < partitionList.size())) {
                Integer currentPartition = partitionList.get(currentIndex);
                Integer currentReplicaType = replicaTypeList.get(currentIndex);

                // Check the current node contains the partition as the
                // requested replicatype
                if(!fetchedPartitions.contains(currentPartition)
                   && RebalanceUtils.checkPartitionBelongsToNode(currentPartition,
                                                                 currentReplicaType,
                                                                 nodeId,
                                                                 initialCluster,
                                                                 storeDef)) {
                    fetchedPartitions.add(currentPartition);
                    found = true;
                    logger.info("Fetching [partition: " + currentPartition + ", replica type:"
                                + currentReplicaType + "] for store " + storageEngine.getName());
                    entriesPartitionIterator = storageEngine.entries(currentPartition);
                }
                currentIndex++;
            }

        } else {
            long startNs = System.nanoTime();
            // do a check before reading in case partition has 0 elements
            if(entriesPartitionIterator.hasNext()) {
                counter++;

                // honor skipRecords
                if(counter % skipRecords == 0) {
                    // do the filtering
                    Pair<ByteArray, Versioned<byte[]>> entry = entriesPartitionIterator.next();
                    stats.recordDiskTime(handle, System.nanoTime() - startNs);
                    ByteArray key = entry.getFirst();
                    Versioned<byte[]> value = entry.getSecond();

                    throttler.maybeThrottle(key.length());
                    if(filter.accept(key, value)) {
                        fetched++;
                        handle.incrementEntriesScanned();
                        VAdminProto.FetchPartitionEntriesResponse.Builder response = VAdminProto.FetchPartitionEntriesResponse.newBuilder();

                        VAdminProto.PartitionEntry partitionEntry = VAdminProto.PartitionEntry.newBuilder()
                                                                                              .setKey(ProtoUtils.encodeBytes(key))
                                                                                              .setVersioned(ProtoUtils.encodeVersioned(value))
                                                                                              .build();
                        response.setPartitionEntry(partitionEntry);
                        Message message = response.build();

                        startNs = System.nanoTime();
                        ProtoUtils.writeMessage(outputStream, message);
                        stats.recordNetworkTime(handle, System.nanoTime() - startNs);
                        throttler.maybeThrottle(AdminServiceRequestHandler.valueSize(value));
                    }
                } else {
                    stats.recordDiskTime(handle, System.nanoTime() - startNs);
                }

                // log progress
                if(0 == counter % 100000) {
                    long totalTime = (System.currentTimeMillis() - startTime) / Time.MS_PER_SECOND;

                    logger.info("Fetch entries scanned " + counter + " entries, fetched " + fetched
                                + " entries for store '" + storageEngine.getName()
                                + "' replicaToPartitionList:" + replicaToPartitionList + " in "
                                + totalTime + " s");
                }
            }

            // reset the iterator if done with this partition
            if(!entriesPartitionIterator.hasNext()) {
                entriesPartitionIterator.close();
                entriesPartitionIterator = null;
            }
        }
        return StreamRequestHandlerState.WRITING;
    }

    @Override
    public final void close(DataOutputStream outputStream) throws IOException {
        if(null != entriesPartitionIterator)
            entriesPartitionIterator.close();
        super.close(outputStream);
    }
}
