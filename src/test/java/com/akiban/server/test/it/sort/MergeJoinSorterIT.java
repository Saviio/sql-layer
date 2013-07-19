/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.akiban.server.test.it.sort;

import com.akiban.qp.operator.API.Ordering;
import com.akiban.qp.operator.API.SortOption;
import com.akiban.qp.operator.Cursor;
import com.akiban.qp.operator.QueryBindings;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.persistitadapter.Sorter;
import com.akiban.qp.persistitadapter.indexcursor.MergeJoinSorter;
import com.akiban.qp.rowtype.RowType;
import com.akiban.util.tap.InOutTap;

public class MergeJoinSorterIT extends SorterITBase {

    @Override
    public Sorter createSorter(QueryContext context, QueryBindings bindings,
            Cursor input, RowType rowType, Ordering ordering,
            SortOption sortOption, InOutTap loadTap) {
        return new MergeJoinSorter(context, bindings, input, rowType, ordering, sortOption, loadTap);
    }

}
