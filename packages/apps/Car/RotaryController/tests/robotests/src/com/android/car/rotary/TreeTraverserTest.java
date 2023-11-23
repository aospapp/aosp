/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.car.rotary;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;

import android.view.accessibility.AccessibilityNodeInfo;

import com.android.car.rotary.TreeTraverser.NodePredicate;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class TreeTraverserTest {
    @Mock
    private NodeCopier mNodeCopier;

    private List<AccessibilityNodeInfo> mNodeList;

    private TreeTraverser mTreeTraverser;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // Utils#copyNode() doesn't work when passed a mock node, so we create a mock method
        // which returns the passed node itself rather than a copy. As a result, nodes created by
        // the mock method (such as |target| in testFindRotateTarget()) shouldn't be recycled.
        doAnswer(returnsFirstArg()).when(mNodeCopier).copy(any(AccessibilityNodeInfo.class));

        mTreeTraverser = new TreeTraverser();
        mTreeTraverser.setNodeCopier(mNodeCopier);

        mNodeList = new ArrayList<>();
    }

    /**
     * Tests
     * {@link TreeTraverser#findNodeOrAncestor(AccessibilityNodeInfo, NodePredicate, NodePredicate)}
     * in the following node tree:
     * <pre>
     *                   node0
     *                  /     \
     *                /         \
     *           node1           node4
     *           /   \           /   \
     *         /       \       /       \
     *      node2    node3   node5    node6
     * </pre>
     */
    @Test
    public void testFindNodeOrAncestor() {
        AccessibilityNodeInfo node0 = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo node1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node0)
                .build();
        AccessibilityNodeInfo node2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node1)
                .build();
        AccessibilityNodeInfo node3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node1)
                .build();
        AccessibilityNodeInfo node4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node0)
                .build();
        AccessibilityNodeInfo node5 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node4)
                .build();
        AccessibilityNodeInfo node6 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node4)
                .build();

        // Should check the node itself.
        AccessibilityNodeInfo result = mTreeTraverser.findNodeOrAncestor(node0,
                /* stopPredicate= */ null, /* targetPredicate= */ node -> node == node0);
        assertThat(result).isSameAs(node0);

        // Parent.
        result = mTreeTraverser.findNodeOrAncestor(node1, /* stopPredicate= */ null,
                /* targetPredicate= */ node -> node == node0);
        assertThat(result).isSameAs(node0);

        // Grandparent.
        result = mTreeTraverser.findNodeOrAncestor(node2, /* stopPredicate= */ null,
                /* targetPredicate= */ node -> node == node0);
        assertThat(result).isSameAs(node0);

        // No ancestor found.
        result = mTreeTraverser.findNodeOrAncestor(node2, /* stopPredicate= */ null,
                /* targetPredicate= */ node -> node == node6);
        assertThat(result).isNull();

        // Stop before target.
        result = mTreeTraverser.findNodeOrAncestor(node2, /* stopPredicate= */ node -> node == node1,
                /* targetPredicate= */ node -> node == node0);
        assertThat(result).isNull();

        // Stop at target.
        result = mTreeTraverser.findNodeOrAncestor(node2, /* stopPredicate= */ node -> node == node0,
                /* targetPredicate= */ node -> node == node0);
        assertThat(result).isNull();
    }

    /**
     * Tests {@link TreeTraverser#depthFirstSearch(AccessibilityNodeInfo, NodePredicate, NodePredicate)}
     * in the following node tree:
     * <pre>
     *                   node0
     *                  /     \
     *                /         \
     *           node1           node4
     *           /   \           /   \
     *         /       \       /       \
     *      node2    node3   node5    node6
     * </pre>
     */
    @Test
    public void testDepthFirstSearch() {
        AccessibilityNodeInfo node0 = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo node1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node0)
                .build();
        AccessibilityNodeInfo node2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node1)
                .build();
        AccessibilityNodeInfo node3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node1)
                .build();
        AccessibilityNodeInfo node4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node0)
                .build();
        AccessibilityNodeInfo node5 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node4)
                .build();
        AccessibilityNodeInfo node6 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node4)
                .build();

        // Iterate in depth-first order, finding nothing.
        List<AccessibilityNodeInfo> targetPredicateCalledWithNodes = new ArrayList<>();
        AccessibilityNodeInfo result = mTreeTraverser.depthFirstSearch(
                node0,
                /* skipPredicate= */ null,
                node -> {
                    targetPredicateCalledWithNodes.add(node);
                    return false;
                });
        assertThat(result).isNull();
        assertThat(targetPredicateCalledWithNodes).containsExactly(
                node0, node1, node2, node3, node4, node5, node6);

        // Find root.
        result = mTreeTraverser.depthFirstSearch(node0, /* skipPredicate= */ null,
                /* targetPredicate= */ node -> node == node0);
        assertThat(result).isSameAs(node0);

        // Find child.
        result = mTreeTraverser.depthFirstSearch(node0, /* skipPredicate= */ null,
                /* targetPredicate= */ node -> node == node4);
        assertThat(result).isSameAs(node4);

        // Find grandchild.
        result = mTreeTraverser.depthFirstSearch(node0, /* skipPredicate= */ null,
                /* targetPredicate= */ node -> node == node6);
        assertThat(result).isSameAs(node6);

        // Iterate in depth-first order, skipping a subtree containing the target
        List<AccessibilityNodeInfo> skipPredicateCalledWithNodes = new ArrayList<>();
        targetPredicateCalledWithNodes.clear();
        result = mTreeTraverser.depthFirstSearch(node0,
                node -> {
                    skipPredicateCalledWithNodes.add(node);
                    return node == node1;
                },
                node -> {
                    targetPredicateCalledWithNodes.add(node);
                    return node == node2;
                });
        assertThat(result).isNull();
        assertThat(skipPredicateCalledWithNodes).containsExactly(node0, node1, node4, node5, node6);
        assertThat(targetPredicateCalledWithNodes).containsExactly(node0, node4, node5, node6);

        // Skip subtree whose root is the target.
        result = mTreeTraverser.depthFirstSearch(node0,
                /* skipPredicate= */ node -> node == node1,
                /* skipPredicate= */ node -> node == node1);
        assertThat(result).isNull();
    }

    /**
     * Tests {@link TreeTraverser#reverseDepthFirstSearch} in the following node tree:
     * <pre>
     *                   node0
     *                  /     \
     *                /         \
     *           node1           node4
     *           /   \           /   \
     *         /       \       /       \
     *      node2    node3   node5    node6
     * </pre>
     */
    @Test
    public void testReverseDepthFirstSearch() {
        AccessibilityNodeInfo node0 = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo node1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node0)
                .build();
        AccessibilityNodeInfo node2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node1)
                .build();
        AccessibilityNodeInfo node3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node1)
                .build();
        AccessibilityNodeInfo node4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node0)
                .build();
        AccessibilityNodeInfo node5 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node4)
                .build();
        AccessibilityNodeInfo node6 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node4)
                .build();

        // Iterate in reverse depth-first order, finding nothing.
        List<AccessibilityNodeInfo> predicateCalledWithNodes = new ArrayList<>();
        AccessibilityNodeInfo result = mTreeTraverser.reverseDepthFirstSearch(
                node0,
                node -> {
                    predicateCalledWithNodes.add(node);
                    return false;
                });
        assertThat(result).isNull();
        assertThat(predicateCalledWithNodes).containsExactly(
                node6, node5, node4, node3, node2, node1, node0);

        // Find root.
        result = mTreeTraverser.reverseDepthFirstSearch(node0, node -> node == node0);
        assertThat(result).isSameAs(node0);

        // Find child.
        result = mTreeTraverser.reverseDepthFirstSearch(node0, node -> node == node1);
        assertThat(result).isSameAs(node1);

        // Find grandchild.
        result = mTreeTraverser.reverseDepthFirstSearch(node0, node -> node == node2);
        assertThat(result).isSameAs(node2);
    }

    /**
     * Tests {@link TreeTraverser#depthFirstSelect} in the following node tree:
     * <pre>
     *                   node0
     *                  /     \
     *                /         \
     *           node1           node4
     *           /   \           /   \
     *         /       \       /       \
     *      node2    node3   node5    node6
     * </pre>
     */
    @Test
    public void testDepthFirstSelect() {
        AccessibilityNodeInfo node0 = new NodeBuilder().setNodeList(mNodeList).build();
        AccessibilityNodeInfo node1 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node0)
                .build();
        AccessibilityNodeInfo node2 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node1)
                .build();
        AccessibilityNodeInfo node3 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node1)
                .build();
        AccessibilityNodeInfo node4 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node0)
                .build();
        AccessibilityNodeInfo node5 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node4)
                .build();
        AccessibilityNodeInfo node6 = new NodeBuilder()
                .setNodeList(mNodeList)
                .setParent(node4)
                .build();

        // Iterate in depth-first order, selecting no nodes.
        List<AccessibilityNodeInfo> predicateCalledWithNodes = new ArrayList<>();
        List<AccessibilityNodeInfo> selectedNodes = new ArrayList<>();
        mTreeTraverser.depthFirstSelect(node0, node -> {
            predicateCalledWithNodes.add(node);
            return false;
        }, selectedNodes);
        assertThat(predicateCalledWithNodes).containsExactly(
                node0, node1, node2, node3, node4, node5, node6);
        assertThat(selectedNodes).isEmpty();

        // Find any node. Selects root and skips descendents.
        predicateCalledWithNodes.clear();
        selectedNodes = new ArrayList<>();
        mTreeTraverser.depthFirstSelect(node0, node -> {
            predicateCalledWithNodes.add(node);
            return true;
        }, selectedNodes);
        assertThat(predicateCalledWithNodes).containsExactly(node0);
        assertThat(selectedNodes).containsExactly(node0);

        // Find children of root node. Skips grandchildren.
        predicateCalledWithNodes.clear();
        selectedNodes = new ArrayList<>();
        mTreeTraverser.depthFirstSelect(node0, node -> {
            predicateCalledWithNodes.add(node);
            return node == node1 || node == node4;
        }, selectedNodes);
        assertThat(predicateCalledWithNodes).containsExactly(node0, node1, node4);
        assertThat(selectedNodes).containsExactly(node1, node4);

        // Find grandchildren of root node.
        selectedNodes = new ArrayList<>();
        mTreeTraverser.depthFirstSelect(node0,
                node -> node == node2 || node == node3 || node == node5 || node == node6,
                selectedNodes);
        assertThat(selectedNodes).containsExactly(node2, node3, node5, node6);
    }
}
