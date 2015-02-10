/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.executor.transport;

import com.carrotsearch.hppc.IntArrayList;
import com.google.common.collect.Iterators;
import io.crate.Constants;
import io.crate.planner.symbol.Reference;
import io.crate.planner.symbol.Symbol;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import org.elasticsearch.action.support.replication.ShardReplicationOperationRequest;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;
import java.util.*;

public class ShardUpsertRequest2 extends ShardReplicationOperationRequest<ShardUpsertRequest2> implements Iterable<ShardUpsertRequest2.Item> {

    /**
     * A single update item.
     */
    static class Item implements Streamable {

        private String id;
        private long version = Versions.MATCH_ANY;

        /**
         * List of objects used for assignment (update or insert)
         */
        private Object[] row;

        /**
         * List of data types of the row values, needed for streaming the values
         */
        private DataType[] dataTypes;


        Item(DataType[] dataTypes) {
            this.dataTypes = dataTypes;
        }

        Item(String id, Object[] row, DataType[] dataTypes, @Nullable Long version) {
            this(dataTypes);
            assert dataTypes.length == row.length : "row length does not match data types length";
            this.id = id;
            this.row = row;
            if (version != null) {
                this.version = version;
            }
        }

        public String id() {
            return id;
        }

        public long version() {
            return version;
        }

        public Object[] row() {
            return row;
        }

        public int retryOnConflict() {
            return version == Versions.MATCH_ANY ? Constants.UPDATE_RETRY_ON_CONFLICT : 0;
        }

        static Item readItem(StreamInput in, DataType[] dataTypes) throws IOException {
            Item item = new Item(dataTypes);
            item.readFrom(in);
            return item;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            id = in.readString();
            version = Versions.readVersion(in);

            int size = in.readVInt();
            row = new Object[size];
            for (int i = 0; i < size; i++) {
                row[i] = dataTypes[i].streamer().readValueFrom(in);
            }

        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(id);
            Versions.writeVersion(version, out);
            out.writeVInt(row.length);
            for (int i = 0; i < row.length; i++) {
                dataTypes[i].streamer().writeValueTo(out, row[i]);
            }

        }
    }

    private int shardId;
    private List<Item> items;
    private IntArrayList locations;
    private boolean continueOnError = false;
    @Nullable
    private String routing;

    /**
     * Map of references and symbols used on update if document exist
     */
    @Nullable
    private Map<Reference, Symbol> updateAssignments;

    /**
     * Map of references and symbols used on insert
     */
    @Nullable
    private Map<Reference, Symbol> insertAssignments;

    /**
     * List of data types of the rows value, needed for streaming
     */
    @Nullable
    private DataType[] dataTypes;

    public ShardUpsertRequest2() {
    }

    public ShardUpsertRequest2(ShardId shardId,
                               DataType[] dataTypes,
                               @Nullable Map<Reference, Symbol> updateAssignments,
                               @Nullable Map<Reference, Symbol> insertAssignments) {
        this(shardId, dataTypes, updateAssignments, insertAssignments, null);
    }

    public ShardUpsertRequest2(ShardId shardId,
                               DataType[] dataTypes,
                               @Nullable Map<Reference, Symbol> updateAssignments,
                               @Nullable Map<Reference, Symbol> insertAssignments,
                               @Nullable String routing) {
        assert updateAssignments != null || insertAssignments != null
                : "Missing assignments, whether for update nor for insert given";
        this.index = shardId.getIndex();
        this.shardId = shardId.id();
        this.routing = routing;
        locations = new IntArrayList();
        this.dataTypes = dataTypes;
        this.updateAssignments = updateAssignments;
        this.insertAssignments = insertAssignments;
        items = new ArrayList<>();
    }

    @Nullable
    public String routing() {
        return routing;
    }

    public List<Item> items() {
        return items;
    }

    public IntArrayList locations() {
        return locations;
    }

    public ShardUpsertRequest2 add(int location,
                                  String id,
                                  Object[] row,
                                  @Nullable Long version,
                                  @Nullable String routing) {
        if (this.routing == null) {
            this.routing = routing;
        }
        locations.add(location);
        items.add(new Item(id, row, dataTypes, version));
        return this;
    }

    public String type() {
        return Constants.DEFAULT_MAPPING_TYPE;
    }

    public int shardId() {
        return shardId;
    }

    @Nullable
    public Map<Reference, Symbol> updateAssignments() {
        return updateAssignments;
    }

    @Nullable
    public Map<Reference, Symbol> insertAssignments() {
        return insertAssignments;
    }

    public boolean continueOnError() {
        return continueOnError;
    }

    public void continueOnError(boolean continueOnError) {
        this.continueOnError = continueOnError;
    }

    @Override
    public Iterator<Item> iterator() {
        return Iterators.unmodifiableIterator(items.iterator());
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        shardId = in.readInt();
        routing = in.readOptionalString();
        int dataTypesSize = in.readVInt();
        dataTypes = new DataType[dataTypesSize];
        for (int i = 0; i < dataTypesSize; i++) {
            dataTypes[i] = DataTypes.fromStream(in);
        }
        int updateAssignmentsSize = in.readVInt();
        if (updateAssignmentsSize > 0) {
            updateAssignments = new HashMap<>();
            for (int i = 0; i < updateAssignmentsSize; i++) {
                updateAssignments.put(Reference.fromStream(in), Symbol.fromStream(in));
            }
        }
        int insertAssignmentsSize = in.readVInt();
        if (insertAssignmentsSize > 0) {
            insertAssignments = new HashMap<>();
            for (int i = 0; i < insertAssignmentsSize; i++) {
                insertAssignments.put(Reference.fromStream(in), Symbol.fromStream(in));
            }
        }

        int size = in.readVInt();
        locations = new IntArrayList(size);
        items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            locations.add(in.readVInt());
            items.add(Item.readItem(in, dataTypes));
        }
        continueOnError = in.readBoolean();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeInt(shardId);
        out.writeOptionalString(routing);
        out.writeVInt(dataTypes.length);
        for (DataType dataType : dataTypes) {
            DataTypes.toStream(dataType, out);
        }
        // Stream assignment symbols
        if (updateAssignments != null) {
            out.writeVInt(updateAssignments.size());
            for (Map.Entry<Reference, Symbol> entry : updateAssignments.entrySet()) {
                Reference.toStream(entry.getKey(), out);
                Symbol.toStream(entry.getValue(), out);
            }
        } else {
            out.writeVInt(0);
        }
        if (insertAssignments != null) {
            out.writeVInt(insertAssignments.size());
            for (Map.Entry<Reference, Symbol> entry : insertAssignments.entrySet()) {
                Reference.toStream(entry.getKey(), out);
                Symbol.toStream(entry.getValue(), out);
            }
        } else {
            out.writeVInt(0);
        }

        out.writeVInt(locations.size());
        for (int i = 0; i < locations.size(); i++) {
            out.writeVInt(locations.get(i));
            items.get(i).writeTo(out);
        }
        out.writeBoolean(continueOnError);
    }

}
