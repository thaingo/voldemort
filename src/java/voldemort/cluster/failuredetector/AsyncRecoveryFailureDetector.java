/*
 * Copyright 2009 Mustard Grain, Inc.
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

package voldemort.cluster.failuredetector;

import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Level;

import voldemort.cluster.Node;
import voldemort.store.Store;
import voldemort.store.UnreachableStoreException;
import voldemort.utils.ByteArray;

/**
 * AsyncRecoveryFailureDetector detects failures and then attempts to contact
 * the failing node's Store to determine availability.
 * 
 * <p/>
 * 
 * When a node does go down, attempts to access the remote Store for that node
 * may take several seconds. Rather than cause the thread to block, we perform
 * this check in a background thread.
 * 
 * @author Kirk True
 */

public class AsyncRecoveryFailureDetector extends AbstractFailureDetector implements Runnable {

    /**
     * A set of nodes that have been marked as unavailable. As nodes go offline
     * and callers report such via recordException, they are added to this set.
     * When a node becomes available via the check in the thread, they are
     * removed.
     */

    private final Set<Node> unavailableNodes;

    /**
     * Thread that checks availability of the nodes in the unavailableNodes set.
     */

    private final Thread recoveryThread;

    public AsyncRecoveryFailureDetector(FailureDetectorConfig failureDetectorConfig) {
        super(failureDetectorConfig);
        unavailableNodes = new HashSet<Node>();

        recoveryThread = new Thread(this);
        recoveryThread.setDaemon(true);
        recoveryThread.start();
    }

    @Override
    public boolean isAvailable(Node node) {
        synchronized(unavailableNodes) {
            return !unavailableNodes.contains(node);
        }
    }

    public void recordException(Node node, Exception e) {
        synchronized(unavailableNodes) {
            unavailableNodes.add(node);
        }

        setUnavailable(node);

        if(logger.isInfoEnabled())
            logger.info(node + " now unavailable");
    }

    public void recordSuccess(Node node) {
    // Do nothing. Nodes only become available in our thread...
    }

    public void run() {
        ByteArray key = new ByteArray((byte) 1);

        while(!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(failureDetectorConfig.getNodeBannagePeriod());
            } catch(InterruptedException e) {
                break;
            }

            Set<Node> unavailableNodesCopy = new HashSet<Node>();

            synchronized(unavailableNodes) {
                unavailableNodesCopy.addAll(unavailableNodes);
            }

            for(Node node: unavailableNodesCopy) {
                if(logger.isInfoEnabled())
                    logger.info("Checking previously unavailable node " + node);

                Store<ByteArray, byte[]> store = failureDetectorConfig.getStore(node);

                if(store == null) {
                    if(logger.isEnabledFor(Level.WARN))
                        logger.warn(node + " store is null; cannot determine node availability");

                    continue;
                }

                try {
                    store.get(key);

                    synchronized(unavailableNodes) {
                        unavailableNodes.remove(node);
                    }

                    setAvailable(node);

                    if(logger.isInfoEnabled())
                        logger.info(node + " now available");
                } catch(UnreachableStoreException e) {
                    if(logger.isEnabledFor(Level.WARN))
                        logger.warn(node + " still unavailable");
                } catch(Exception e) {
                    if(logger.isEnabledFor(Level.ERROR))
                        logger.error(node + " unavailable due to error", e);
                }
            }
        }
    }

}
