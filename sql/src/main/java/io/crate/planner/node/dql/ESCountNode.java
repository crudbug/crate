/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.planner.node.dql;

import com.google.common.collect.Iterators;
import io.crate.analyze.WhereClause;
import io.crate.core.StringUtils;
import io.crate.planner.node.PlanNodeVisitor;
import io.crate.planner.projection.Projection;
import io.crate.planner.symbol.StringValueSymbolVisitor;
import io.crate.types.DataType;
import io.crate.types.LongType;

import java.util.Arrays;
import java.util.List;

public class ESCountNode extends ESDQLPlanNode {

    private final List<DataType> outputTypes = Arrays.<DataType>asList(LongType.INSTANCE);
    private final String[] indices;
    private final WhereClause whereClause;
    private final String routing;

    public ESCountNode(String[] indices, WhereClause whereClause) {
        this.indices = indices;
        this.whereClause = whereClause;
        if (whereClause.clusteredBy().isPresent()){
            routing = StringUtils.ROUTING_JOINER.join(Iterators.transform(
                    whereClause.clusteredBy().get().iterator(), StringValueSymbolVisitor.PROCESS_FUNCTION));
        } else {
            this.routing = null;
        }


    }

    @Override
    public <C, R> R accept(PlanNodeVisitor<C, R> visitor, C context) {
        return visitor.visitESCountNode(this, context);
    }

    public String[] indices() {
        return indices;
    }

    public String routing() {
        return routing;
    }

    public WhereClause whereClause() {
        return whereClause;
    }

    @Override
    public List<DataType> outputTypes() {
        return outputTypes;
    }

    @Override
    public void addProjection(Projection projection) {
        throw new UnsupportedOperationException("adding projection not supported");
    }
}
