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

package com.akiban.sql.optimizer.plan;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.HKey;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.Table;
import com.akiban.ais.model.UserTable;
import com.akiban.sql.optimizer.plan.MultiIndexEnumerator.BranchInfo;
import com.akiban.sql.optimizer.rule.EquivalenceFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>An enumerator which, given an Index and a set of conditions, will produce a collection of
 * {@link MultiIndexCandidate} which represent valid multi-index intersection pairs.</p>
 * 
 * <p>Like {@link MultiIndexCandidate}, this class is generic and abstract; its only abstract method is one for
 * creating an empty {@link MultiIndexCandidate}. Also like that class, the expectation is that there will be two
 * subclasses for this class: one for unit testing, and one for production.</p>
 * @param <C> the condition type.
 */
public abstract class MultiIndexEnumerator<C,B extends BranchInfo<C>, N extends IndexIntersectionNode<C,N>> {
    
    public interface BranchInfo<C> {
        Column columnFromCondition(C condition);
        Collection<? extends Index> getIndexes();
        Set<C> getConditions();
    }
    
    Logger log = LoggerFactory.getLogger(MultiIndexEnumerator.class);

    protected abstract N buildLeaf(MultiIndexCandidate<C> candidate, B branchInfo);
    protected abstract N intersect(N first, N second, int comparisonCount);

    private List<N> results = new ArrayList<N>();
    private Set<C> conditions = new HashSet<C>();
    
    public void addBranch(B branchInfo)
    {
        Set<C> branchConditions = branchInfo.getConditions();
        conditions.addAll(branchConditions);
        Map<Column,C> colsToConds = new HashMap<Column,C>(branchConditions.size());
        for (C cond : branchConditions) {
            Column column = branchInfo.columnFromCondition(cond);
            if (column == null) {
                log.warn("couldn't map <{}> to Column", cond);
                continue;
            }
            C old = colsToConds.put(column, cond);
            if (old != null) {
                handleDuplicateCondition(); // test hook
            }
        }
        
        
        // Seed the results with single-index scans. Remember how many there are, so that we can later crop those out.
        for (Index index : branchInfo.getIndexes()) {
            MultiIndexCandidate<C> candidate = createCandidate(index, colsToConds);
            if (candidate.anyPegged()) {
                N leaf = buildLeaf(candidate, branchInfo);
                results.add(leaf);
            }
        }
    }
    
    public Collection<N> getCombinations(EquivalenceFinder<Column> columnEquivalences) {
        if (results.isEmpty())
            return Collections.emptyList(); // return early if there's nothing here, cause why not.
        final int leaves = results.size();
        
        List<N> freshNodes = results;
        List<N> oldNodes = results;
        List<N> newNodes = new ArrayList<N>(leaves);
        Set<C> conditionsCopy = new HashSet<C>(conditions);
        List<C> outerRecycle = new ArrayList<C>(conditions.size());
        List<C> innerRecycle = new ArrayList<C>(conditions.size());
        do {
            newNodes.clear();
            for (N outer : freshNodes) {
                if (outer.removeCoveredConditions(conditionsCopy, outerRecycle) && (!conditionsCopy.isEmpty())) {
                    for (N inner : oldNodes) {
                        if (inner != outer && inner.removeCoveredConditions(conditionsCopy, innerRecycle)) { // TODO if outer pegs [A] and inner pegs [A,B], this will emit, but it shouldn't.
                            emit(outer, inner, newNodes, columnEquivalences);
                            emptyInto(innerRecycle,conditionsCopy);
                        }
                    }
                }
                emptyInto(outerRecycle, conditionsCopy);
            }
            int oldCount = results.size();
            results.addAll(newNodes);
            oldNodes = results.subList(0, oldCount);
            freshNodes = results.subList(oldCount, results.size());
        } while (!newNodes.isEmpty());
        
        return results.subList(leaves, results.size());
    }
    
    private static <T> void emptyInto(Collection<? extends T> source, Collection<? super T> target) {
        target.addAll(source);
        source.clear();
    }

    protected void handleDuplicateCondition() {
    }

    private MultiIndexCandidate<C> createCandidate(Index index, Map<Column, C> colsToConds) {
        MultiIndexCandidate<C> result = new MultiIndexCandidate<C>(index);
        while(true) {
            Column nextCol = result.getNextFreeColumn();
            if (nextCol == null)
                break;
            C condition = colsToConds.get(nextCol);
            if (condition == null)
                break;
            result.peg(condition);
        }
        return result;
    }

    private void emit(N first, N second, Collection<N> output, EquivalenceFinder<Column> columnEquivalences)
    {
        Table firstTable = first.getLeafMostUTable();
        Table secondTable = second.getLeafMostUTable();
        List<Column> comparisonCols = getComparisonColumns(first, second, columnEquivalences);
        if (comparisonCols.isEmpty())
            return;
        UserTable firstUTable = (UserTable) firstTable;
        UserTable secondUTable = (UserTable) secondTable;
        int comparisonsLen = comparisonCols.size();
        // find the UserTable associated with the common N. This works for multi- as well as single-branch
        UserTable commonAncestor = first.findCommonAncestor(second);
        assert commonAncestor == second.findCommonAncestor(first) : first + "'s ancestor not reflexive with " + second;
        boolean isMultiBranch = true;
        if (firstUTable != secondUTable) {
            if (commonAncestor == firstUTable) {
                isMultiBranch = false;
                if (includesHKey(firstUTable, comparisonCols, columnEquivalences))
                    output.add(intersect(second, first, comparisonsLen));
            }
            else if (commonAncestor == secondUTable) {
                isMultiBranch = false;
                if (includesHKey(secondUTable, comparisonCols, columnEquivalences))
                    output.add(intersect(first, second, comparisonsLen));
            }
        }
        if (isMultiBranch) {
            Collection<Column> ancestorHKeys = ancestorHKeys(commonAncestor);
            // check if commonTrailing contains all ancestorHKeys, using equivalence
            for (Column hkeyCol : ancestorHKeys) {
                boolean found = false;
                for (Column commonCol : comparisonCols) {
                    if (columnEquivalences.areEquivalent(commonCol, hkeyCol)) {
                        found = true;
                        break;
                    }
                }
                if (!found)
                    return;
            }
            output.add(intersect(first, second, comparisonsLen));
            output.add(intersect(second, first, comparisonsLen));
        }
    }

    private Collection<Column> ancestorHKeys(UserTable ancefoo) {
        // find most common ancestor
        HKey hkey = ancefoo.hKey();
        int ncols = hkey.nColumns();
        List<Column> results = new ArrayList<Column>(ncols);
        for (int i=0; i < ncols; ++i) {
            results.add(hkey.column(i));
        }
        return results;
    }

    private boolean includesHKey(UserTable table, List<Column> columns, EquivalenceFinder<Column> columnEquivalences) {
        HKey hkey = table.hKey();
        int ncols = hkey.nColumns();
        // no overhead, but O(N) per hkey segment. assuming ncols and columns is very small
        for (int i = 0; i < ncols; ++i) {
            if (!containsEquivalent(columns, hkey.column(i), columnEquivalences))
                return false;
        }
        return true;
    }

    private boolean containsEquivalent(List<Column> cols, Column tgt, EquivalenceFinder<Column> columnEquivalences) {
        for (Column col : cols) {
            if (columnEquivalences.areEquivalent(col, tgt))
                return true;
        }
        return false;
    }

    protected abstract List<Column> getComparisonColumns(N first, N second, EquivalenceFinder<Column> equivalencies);
}
