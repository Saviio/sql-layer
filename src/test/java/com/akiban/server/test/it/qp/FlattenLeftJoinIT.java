/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.test.it.qp;

/*
 * This test is inspired by Bug 837706, in which a left join row was not being ordered correctly relative
 * to siblings. The schema for this test uses this grouping:
 *     ancestor
 *         parent
 *             before_child
 *             child
 *             after_child
 * and checks that a parent/child left join row is ordered correctly in the presence of any combination
 * of before_child and after_child rows.
 */

import com.akiban.ais.model.GroupTable;
import com.akiban.qp.expression.Comparison;
import com.akiban.qp.expression.Expression;
import com.akiban.qp.persistitadapter.OperatorStore;
import com.akiban.qp.persistitadapter.PersistitAdapter;
import com.akiban.qp.physicaloperator.PhysicalOperator;
import com.akiban.qp.row.RowBase;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.Schema;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.store.PersistitStore;
import com.akiban.server.store.Store;
import org.junit.Before;
import org.junit.Test;

import static com.akiban.qp.physicaloperator.API.FlattenOption.KEEP_PARENT;
import static com.akiban.qp.physicaloperator.API.JoinType.INNER_JOIN;
import static com.akiban.qp.physicaloperator.API.JoinType.LEFT_JOIN;
import static com.akiban.qp.physicaloperator.API.*;
import static com.akiban.qp.expression.API.*;

public class FlattenLeftJoinIT extends PhysicalOperatorITBase
{
    @Before
    public void before()
    {
        // Don't call super.before(). This is a different schema from most operator ITs.
        ancestor = createTable(
            "schema", "ancestor",
            "aid int not null key",
            "avalue varchar(20)");
        parent = createTable(
            "schema", "parent",
            "pid int not null key",
            "aid int",
            "pvalue varchar(20)",
            "constraint __akiban_pa foreign key __akiban_pa(aid) references ancestor(aid)");
        beforeChild = createTable(
            "schema", "before_child",
            "bid int not null key",
            "pid int",
            "bvalue varchar(20)",
            "constraint __akiban_bp foreign key __akiban_bp(pid) references parent(pid)");
        child = createTable(
            "schema", "child",
            "cid int not null key",
            "pid int",
            "cvalue varchar(20)",
            "constraint __akiban_cp foreign key __akiban_cp(pid) references parent(pid)");
        afterChild = createTable(
            "schema", "after_child",
            "aid int not null key",
            "pid int",
            "avalue varchar(20)",
            "constraint __akiban_ap foreign key __akiban_ap(pid) references parent(pid)");
        schema = new Schema(rowDefCache().ais());
        ancestorRowType = schema.userTableRowType(userTable(ancestor));
        parentRowType = schema.userTableRowType(userTable(parent));
        beforeChildRowType = schema.userTableRowType(userTable(beforeChild));
        childRowType = schema.userTableRowType(userTable(child));
        afterChildRowType = schema.userTableRowType(userTable(afterChild));
        group = groupTable(ancestor);
        db = new NewRow[]{
            // case 1: one row of each type (except child)
            createNewRow(ancestor, 1L, "a1"),
            createNewRow(parent, 11L, 1L, "p1"),
            createNewRow(beforeChild, 111L, 11L, "b1"),
            createNewRow(afterChild, 111L, 11L, "a1"),
            // case 2: no before_child row
            createNewRow(ancestor, 2L, "a2"),
            createNewRow(parent, 22L, 2L, "p2"),
            createNewRow(afterChild, 222L, 22L, "a2"),
            // case 3: no after_child row
            createNewRow(ancestor, 3L, "a3"),
            createNewRow(parent, 33L, 3L, "p3"),
            createNewRow(beforeChild, 333L, 33L, "b3"),
            // case 4: no before_child or after_child row
            createNewRow(ancestor, 4L, "a4"),
            createNewRow(parent, 41L, 4L, "p41"),
            createNewRow(parent, 42L, 4L, "p42"),
        };
        Store plainStore = store();
        final PersistitStore persistitStore;
        if (plainStore instanceof OperatorStore) {
            OperatorStore operatorStore = (OperatorStore) plainStore;
            persistitStore = operatorStore.getPersistitStore();
        } else {
            persistitStore = (PersistitStore) plainStore;
        }
        adapter = new PersistitAdapter(schema, persistitStore, session());
        use(db);
    }

    @Test
    public void testCase1()
    {
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(1)),
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 1L, "a1"),
            row(parentRowType, 11L, 1L, "p1"),
            row(beforeChildRowType, 111L, 11L, "b1"),
            row(pcRowType, 11L, 1L, "p1", null, null, null),
            row(afterChildRowType, 111L, 11L, "a1"),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase2()
    {
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(2)),
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 2L, "a2"),
            row(parentRowType, 22L, 2L, "p2"),
            row(pcRowType, 22L, 2L, "p2", null, null, null),
            row(afterChildRowType, 222L, 22L, "a2"),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase3()
    {
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(3)),
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 3L, "a3"),
            row(parentRowType, 33L, 3L, "p3"),
            row(beforeChildRowType, 333L, 33L, "b3"),
            row(pcRowType, 33L, 3L, "p3", null, null, null),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase4()
    {
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(4)),
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 4L, "a4"),
            row(parentRowType, 41L, 4L, "p41"),
            row(pcRowType, 41L, 4L, "p41", null, null, null),
            row(parentRowType, 42L, 4L, "p42"),
            row(pcRowType, 42L, 4L, "p42", null, null, null),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase1FlattenPB()
    {
        PhysicalOperator flattenPB =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(1)),
                parentRowType,
                beforeChildRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                flattenPB,
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType pbRowType = flattenPB.rowType();
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 1L, "a1"),
            row(parentRowType, 11L, 1L, "p1"),
            row(pbRowType, 11L, 1L, "p1", 111L, 11L, "b1"),
            row(pcRowType, 11L, 1L, "p1", null, null, null),
            row(afterChildRowType, 111L, 11L, "a1"),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase2FlattenPB()
    {
        PhysicalOperator flattenPB =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(2)),
                parentRowType,
                beforeChildRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                flattenPB,
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType pbRowType = flattenPB.rowType();
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 2L, "a2"),
            row(parentRowType, 22L, 2L, "p2"),
            row(pbRowType, 22L, 2L, "p2", null, null, null),
            row(pcRowType, 22L, 2L, "p2", null, null, null),
            row(afterChildRowType, 222L, 22L, "a2"),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase3FlattenPB()
    {
        PhysicalOperator flattenPB =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(3)),
                parentRowType,
                beforeChildRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                flattenPB,
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType pbRowType = flattenPB.rowType();
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 3L, "a3"),
            row(parentRowType, 33L, 3L, "p3"),
            row(pbRowType, 33L, 3L, "p3", 333L, 33L, "b3"),
            row(pcRowType, 33L, 3L, "p3", null, null, null),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase4FlattenPB()
    {
        PhysicalOperator flattenPB =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(4)),
                parentRowType,
                beforeChildRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                flattenPB,
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType pbRowType = flattenPB.rowType();
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 4L, "a4"),
            row(parentRowType, 41L, 4L, "p41"),
            row(pbRowType, 41L, 4L, "p41", null, null, null),
            row(pcRowType, 41L, 4L, "p41", null, null, null),
            row(parentRowType, 42L, 4L, "p42"),
            row(pbRowType, 42L, 4L, "p42", null, null, null),
            row(pcRowType, 42L, 4L, "p42", null, null, null),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase1FlattenPA()
    {
        PhysicalOperator flattenPA =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(1)),
                parentRowType,
                afterChildRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                flattenPA,
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType paRowType = flattenPA.rowType();
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 1L, "a1"),
            row(parentRowType, 11L, 1L, "p1"),
            row(beforeChildRowType, 111L, 11L, "b1"),
            row(pcRowType, 11L, 1L, "p1", null, null, null),
            row(paRowType, 11L, 1L, "p1", 111L, 11L, "a1"),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase2FlattenPA()
    {
        PhysicalOperator flattenPA =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(2)),
                parentRowType,
                afterChildRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                flattenPA,
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType paRowType = flattenPA.rowType();
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 2L, "a2"),
            row(parentRowType, 22L, 2L, "p2"),
            row(pcRowType, 22L, 2L, "p2", null, null, null),
            row(paRowType, 22L, 2L, "p2", 222L, 22L, "a2"),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase3FlattenPA()
    {
        PhysicalOperator flattenPA =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(3)),
                parentRowType,
                afterChildRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                flattenPA,
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType paRowType = flattenPA.rowType();
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 3L, "a3"),
            row(parentRowType, 33L, 3L, "p3"),
            row(beforeChildRowType, 333L, 33L, "b3"),
            row(pcRowType, 33L, 3L, "p3", null, null, null),
            row(paRowType, 33L, 3L, "p3", null, null, null),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    @Test
    public void testCase4FlattenPA()
    {
        PhysicalOperator flattenPA =
            flatten_HKeyOrdered(
                select_HKeyOrdered(
                    groupScan_Default(group),
                    ancestorRowType,
                    selectAncestor(4)),
                parentRowType,
                afterChildRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        PhysicalOperator plan =
            flatten_HKeyOrdered(
                flattenPA,
                parentRowType,
                childRowType,
                LEFT_JOIN,
                KEEP_PARENT);
        RowType paRowType = flattenPA.rowType();
        RowType pcRowType = plan.rowType();
        RowBase[] expected = new RowBase[]{
            row(ancestorRowType, 4L, "a4"),
            row(parentRowType, 41L, 4L, "p41"),
            row(pcRowType, 41L, 4L, "p41", null, null, null),
            row(paRowType, 41L, 4L, "p41", null, null, null),
            row(parentRowType, 42L, 4L, "p42"),
            row(pcRowType, 42L, 4L, "p42", null, null, null),
            row(paRowType, 42L, 4L, "p42", null, null, null),
        };
        compareRows(expected, cursor(plan, adapter));
    }

    // For use by this class

    private Expression selectAncestor(long aid)
    {
        return compare(field(0), Comparison.EQ, literal(aid));
    }

    // Object state

    private int ancestor;
    private int parent;
    private int beforeChild;
    private int child;
    private int afterChild;
    private RowType ancestorRowType;
    private RowType parentRowType;
    private RowType beforeChildRowType;
    private RowType childRowType;
    private RowType afterChildRowType;
    private GroupTable group;
}