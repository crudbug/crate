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

import io.crate.Constants;
import io.crate.metadata.ReferenceIdent;
import io.crate.metadata.ReferenceInfo;
import io.crate.metadata.TableIdent;
import io.crate.planner.RowGranularity;
import io.crate.planner.symbol.InputColumn;
import io.crate.planner.symbol.Reference;
import io.crate.planner.symbol.Symbol;
import io.crate.types.DataType;
import io.crate.types.DataTypes;
import io.crate.types.IntegerType;
import io.crate.types.StringType;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.io.stream.BytesStreamInput;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.index.shard.ShardId;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

public class ShardUpsertRequest2Test {

    TableIdent charactersIdent = new TableIdent(null, "characters");

    Reference idRef = new Reference(new ReferenceInfo(
            new ReferenceIdent(charactersIdent, "id"), RowGranularity.DOC, DataTypes.INTEGER));
    Reference nameRef = new Reference(new ReferenceInfo(
            new ReferenceIdent(charactersIdent, "name"), RowGranularity.DOC, DataTypes.STRING));

    @Test
    public void testStreamingOfInsert() throws Exception {
        ShardId shardId = new ShardId("test", 1);
        DataType[] dataTypes = new DataType[]{ IntegerType.INSTANCE, StringType.INSTANCE };
        Map<Reference, Symbol> insertAssignments = new HashMap<Reference, Symbol>(){{
            put(idRef, new InputColumn(0));
            put(nameRef, new InputColumn(1));
        }};
        ShardUpsertRequest2 request = new ShardUpsertRequest2(
                shardId,
                dataTypes,
                null,
                insertAssignments);

        request.add(123, "99",
                new Object[]{99, new BytesRef("Marvin")},
                null, "99");

        BytesStreamOutput out = new BytesStreamOutput();
        request.writeTo(out);

        BytesStreamInput in = new BytesStreamInput(out.bytes());
        ShardUpsertRequest2 request2 = new ShardUpsertRequest2();
        request2.readFrom(in);

        assertThat(request2.index(), is(shardId.getIndex()));
        assertThat(request2.shardId(), is(shardId.id()));
        assertThat(request2.routing(), is("99"));
        assertNull(request2.updateAssignments());
        assertThat(request2.insertAssignments(), is(insertAssignments));

        assertThat(request2.locations().size(), is(1));
        assertThat(request2.locations().get(0), is(123));

        assertThat(request2.items().size(), is(1));

        ShardUpsertRequest2.Item item1 = request2.items().get(0);
        assertThat(item1.id(), is("99"));
        assertThat(item1.row(), is(new Object[]{99, new BytesRef("Marvin")}));
        assertThat(item1.version(), is(Versions.MATCH_ANY));
        assertThat(item1.retryOnConflict(), is(Constants.UPDATE_RETRY_ON_CONFLICT));
    }

    @Test
    public void testStreamingOfUpdate() throws Exception {
        ShardId shardId = new ShardId("test", 1);
        DataType[] dataTypes = new DataType[]{ StringType.INSTANCE };
        Map<Reference, Symbol> updateAssignments = new HashMap<Reference, Symbol>(){{ put(nameRef, new InputColumn(1)); }};

        ShardUpsertRequest2 request = new ShardUpsertRequest2(
                shardId,
                dataTypes,
                updateAssignments,
                null,
                "99");

        request.add(123, "99",
                new Object[]{ new BytesRef("Marvin") },
                2L, null);


        BytesStreamOutput out = new BytesStreamOutput();
        request.writeTo(out);

        BytesStreamInput in = new BytesStreamInput(out.bytes());
        ShardUpsertRequest2 request2 = new ShardUpsertRequest2();
        request2.readFrom(in);

        assertThat(request2.index(), is(shardId.getIndex()));
        assertThat(request2.shardId(), is(shardId.id()));
        assertThat(request2.routing(), is("99"));
        assertThat(request2.updateAssignments(), is(updateAssignments));
        assertNull(request2.insertAssignments());

        assertThat(request2.locations().size(), is(1));
        assertThat(request2.locations().get(0), is(123));

        assertThat(request2.items().size(), is(1));

        ShardUpsertRequest2.Item item1 = request2.items().get(0);
        assertThat(item1.id(), is("99"));
        assertThat(item1.row(), is(new Object[]{ new BytesRef("Marvin") }));
        assertThat(item1.version(), is(2L));
        assertThat(item1.retryOnConflict(), is(0));
    }

    @Test
    public void testStreamingOfUpsert() throws Exception {
        ShardId shardId = new ShardId("test", 1);
        DataType[] dataTypes = new DataType[]{ IntegerType.INSTANCE, StringType.INSTANCE };
        Map<Reference, Symbol> updateAssignments = new HashMap<Reference, Symbol>(){{
            put(nameRef, new InputColumn(1));
        }};
        Map<Reference, Symbol> insertAssignments = new HashMap<Reference, Symbol>(){{
            put(idRef, new InputColumn(0));
            put(nameRef, new InputColumn(1));
        }};
        ShardUpsertRequest2 request = new ShardUpsertRequest2(
                shardId,
                dataTypes,
                updateAssignments,
                insertAssignments,
                "99");

        request.add(123, "99",
                new Object[]{ 99, new BytesRef("Marvin") },
                2L, null);


        BytesStreamOutput out = new BytesStreamOutput();
        request.writeTo(out);

        BytesStreamInput in = new BytesStreamInput(out.bytes());
        ShardUpsertRequest2 request2 = new ShardUpsertRequest2();
        request2.readFrom(in);

        assertThat(request2.index(), is(shardId.getIndex()));
        assertThat(request2.shardId(), is(shardId.id()));
        assertThat(request2.routing(), is("99"));
        assertThat(request2.updateAssignments(), is(updateAssignments));
        assertThat(request2.insertAssignments(), is(insertAssignments));

        assertThat(request2.locations().size(), is(1));
        assertThat(request2.locations().get(0), is(123));

        assertThat(request2.items().size(), is(1));

        ShardUpsertRequest2.Item item1 = request2.items().get(0);
        assertThat(item1.id(), is("99"));
        assertThat(item1.row(), is(new Object[]{ 99, new BytesRef("Marvin") }));
        assertThat(item1.version(), is(2L));
        assertThat(item1.retryOnConflict(), is(0));
    }

}
