/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.routing.allocation.command;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.ElasticSearchParseException;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.MutableShardRouting;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.Iterator;

import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.RELOCATING;

/**
 * A command that cancel relocation, or recovery of a given shard on a node.
 */
public class CancelAllocationCommand implements AllocationCommand {

    public static final String NAME = "cancel";

    public static class Factory implements AllocationCommand.Factory<CancelAllocationCommand> {

        @Override
        public CancelAllocationCommand readFrom(StreamInput in) throws IOException {
            return new CancelAllocationCommand(ShardId.readShardId(in), in.readString());
        }

        @Override
        public void writeTo(CancelAllocationCommand command, StreamOutput out) throws IOException {
            command.shardId().writeTo(out);
            out.writeString(command.node());
        }

        @Override
        public CancelAllocationCommand fromXContent(XContentParser parser) throws IOException {
            String index = null;
            int shardId = -1;
            String nodeId = null;

            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token.isValue()) {
                    if ("index".equals(currentFieldName)) {
                        index = parser.text();
                    } else if ("shard".equals(currentFieldName)) {
                        shardId = parser.intValue();
                    } else if ("node".equals(currentFieldName)) {
                        nodeId = parser.text();
                    } else {
                        throw new ElasticSearchParseException("[cancel] command does not support field [" + currentFieldName + "]");
                    }
                } else {
                    throw new ElasticSearchParseException("[cancel] command does not support complex json tokens [" + token + "]");
                }
            }
            if (index == null) {
                throw new ElasticSearchParseException("[cancel] command missing the index parameter");
            }
            if (shardId == -1) {
                throw new ElasticSearchParseException("[cancel] command missing the shard parameter");
            }
            if (nodeId == null) {
                throw new ElasticSearchParseException("[cancel] command missing the node parameter");
            }
            return new CancelAllocationCommand(new ShardId(index, shardId), nodeId);
        }

        @Override
        public void toXContent(CancelAllocationCommand command, XContentBuilder builder, ToXContent.Params params) throws IOException {
            builder.startObject();
            builder.field("index", command.shardId().index());
            builder.field("shard", command.shardId().id());
            builder.field("node", command.node());
            builder.endObject();
        }
    }


    private final ShardId shardId;
    private final String node;

    public CancelAllocationCommand(ShardId shardId, String node) {
        this.shardId = shardId;
        this.node = node;
    }

    @Override
    public String name() {
        return NAME;
    }

    public ShardId shardId() {
        return this.shardId;
    }

    public String node() {
        return this.node;
    }

    @Override
    public void execute(RoutingAllocation allocation) throws ElasticSearchException {
        DiscoveryNode discoNode = allocation.nodes().resolveNode(node);

        boolean found = false;
        for (Iterator<MutableShardRouting> it = allocation.routingNodes().node(discoNode.id()).iterator(); it.hasNext(); ) {
            MutableShardRouting shardRouting = it.next();
            if (!shardRouting.shardId().equals(shardId)) {
                continue;
            }
            found = true;
            if (shardRouting.relocatingNodeId() != null) {
                if (shardRouting.initializing()) {
                    // the shard is initializing and recovering from another node, simply cancel the recovery
                    it.remove();
                    shardRouting.deassignNode();
                    // and cancel the relocating state from the shard its being relocated from
                    RoutingNode relocatingFromNode = allocation.routingNodes().node(shardRouting.relocatingNodeId());
                    if (relocatingFromNode != null) {
                        for (MutableShardRouting fromShardRouting : relocatingFromNode) {
                            if (fromShardRouting.shardId().equals(shardRouting.shardId()) && shardRouting.state() == RELOCATING) {
                                fromShardRouting.cancelRelocation();
                                break;
                            }
                        }
                    }
                } else if (shardRouting.relocating()) {
                    // the shard is relocating to another node, cancel the recovery on the other node, and deallocate this one
                    if (shardRouting.primary()) {
                        // can't cancel a primary shard being initialized
                        throw new ElasticSearchIllegalArgumentException("[cancel_allocation] can't cancel " + shardId + " on node " + discoNode + ", shard is primary and initializing its state");
                    }
                    it.remove();
                    allocation.routingNodes().unassigned().add(new MutableShardRouting(shardRouting.index(), shardRouting.id(),
                            null, shardRouting.primary(), ShardRoutingState.UNASSIGNED, shardRouting.version() + 1));

                    // now, go and find the shard that is initializing on the target node, and cancel it as well...
                    RoutingNode initializingNode = allocation.routingNodes().node(shardRouting.relocatingNodeId());
                    if (initializingNode != null) {
                        for (Iterator<MutableShardRouting> itX = initializingNode.iterator(); itX.hasNext(); ) {
                            MutableShardRouting initializingShardRouting = itX.next();
                            if (initializingShardRouting.shardId().equals(shardRouting.shardId()) && initializingShardRouting.state() == INITIALIZING) {
                                shardRouting.deassignNode();
                                itX.remove();
                            }
                        }
                    }
                }
            } else {
                // the shard is not relocating, its either started, or initializing, just cancel it and move on...
                if (shardRouting.primary()) {
                    // can't cancel a primary shard being initialized
                    throw new ElasticSearchIllegalArgumentException("[cancel_allocation] can't cancel " + shardId + " on node " + discoNode + ", shard is primary and initializing its state");
                }
                it.remove();
                allocation.routingNodes().unassigned().add(new MutableShardRouting(shardRouting.index(), shardRouting.id(),
                        null, shardRouting.primary(), ShardRoutingState.UNASSIGNED, shardRouting.version() + 1));
            }
        }

        if (!found) {
            throw new ElasticSearchIllegalArgumentException("[cancel_allocation] can't cancel " + shardId + ", failed to find it on node " + discoNode);
        }
    }
}
