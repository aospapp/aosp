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

import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD;
import static android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.ui.FocusArea;
import com.android.car.ui.FocusParkingView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A helper class used for finding the next focusable node when the rotary controller is rotated or
 * nudged.
 */
class Navigator {

    @NonNull
    private NodeCopier mNodeCopier = new NodeCopier();

    @NonNull
    private final TreeTraverser mTreeTraverser = new TreeTraverser();

    private final RotaryCache mRotaryCache;

    private final int mHunLeft;
    private final int mHunRight;

    @View.FocusRealDirection
    private int mHunNudgeDirection;

    Navigator(@RotaryCache.CacheType int focusHistoryCacheType,
            int focusHistoryCacheSize,
            int focusHistoryExpirationTimeMs,
            @RotaryCache.CacheType int focusAreaHistoryCacheType,
            int focusAreaHistoryCacheSize,
            int focusAreaHistoryExpirationTimeMs,
            @RotaryCache.CacheType int focusWindowCacheType,
            int focusWindowCacheSize,
            int focusWindowExpirationTimeMs,
            int hunLeft,
            int hunRight,
            boolean showHunOnBottom) {
        mRotaryCache = new RotaryCache(focusHistoryCacheType,
                focusHistoryCacheSize,
                focusHistoryExpirationTimeMs,
                focusAreaHistoryCacheType,
                focusAreaHistoryCacheSize,
                focusAreaHistoryExpirationTimeMs,
                focusWindowCacheType,
                focusWindowCacheSize,
                focusWindowExpirationTimeMs);
        mHunLeft = hunLeft;
        mHunRight = hunRight;
        mHunNudgeDirection = showHunOnBottom ? View.FOCUS_DOWN : View.FOCUS_UP;
    }

    /** Clears focus area history cache. */
    void clearFocusAreaHistory() {
        mRotaryCache.clearFocusAreaHistory();
    }

    /** Caches the focused node by focus area and by window. */
    void saveFocusedNode(@NonNull AccessibilityNodeInfo focusedNode) {
        long elapsedRealtime = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo focusArea = getAncestorFocusArea(focusedNode);
        mRotaryCache.saveFocusedNode(focusArea, focusedNode, elapsedRealtime);
        mRotaryCache.saveWindowFocus(focusedNode, elapsedRealtime);
        Utils.recycleNode(focusArea);
    }

    /**
     * Returns the most recently focused valid node or {@code null} if there are no valid nodes
     * saved by {@link #saveFocusedNode}. The caller is responsible for recycling the result.
     */
    AccessibilityNodeInfo getMostRecentFocus() {
        return mRotaryCache.getMostRecentFocus(SystemClock.elapsedRealtime());
    }

    /**
     * Returns the target focusable for a nudge:
     * <ol>
     *     <li>If the HUN is present and the nudge is towards it, a focusable in the HUN is
     *         returned. See {@link #findHunNudgeTarget} for details.
     *     <li>Otherwise, a target focus area is chosen, either from the focus area history or by
     *         choosing the best candidate. See {@link #findNudgeTargetFocusArea} for details.
     *     <li>Finally a focusable view within the chosen focus area is chosen, either from the
     *         focus history or by choosing the best candidate.
     * </ol>
     * The caller is responsible for recycling the result.
     *
     * @param windows    a list of windows to search from
     * @param sourceNode the current focus
     * @param direction  nudge direction, must be {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
     *                   {@link View#FOCUS_LEFT}, or {@link View#FOCUS_RIGHT}
     * @return a view that can take focus (visible, focusable and enabled) within another {@link
     *         FocusArea}, which is in the given {@code direction} from the current {@link
     *         FocusArea}, or null if not found
     */
    AccessibilityNodeInfo findNudgeTarget(@NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityNodeInfo sourceNode, int direction) {
        // If the user is trying to nudge to the HUN, search for a focus area in the HUN window.
        AccessibilityNodeInfo hunNudgeTarget = findHunNudgeTarget(windows, sourceNode, direction);
        if (hunNudgeTarget != null) {
            return hunNudgeTarget;
        }

        long elapsedRealtime = SystemClock.elapsedRealtime();
        AccessibilityNodeInfo currentFocusArea = getAncestorFocusArea(sourceNode);
        AccessibilityNodeInfo targetFocusArea =
                findNudgeTargetFocusArea(windows, sourceNode, currentFocusArea, direction);
        Utils.recycleNode(currentFocusArea);
        if (targetFocusArea == null) {
            return null;
        }

        // Return the recently focused node within the target focus area, if any.
        AccessibilityNodeInfo cachedFocusedNode =
                mRotaryCache.getFocusedNode(targetFocusArea, elapsedRealtime);
        if (cachedFocusedNode != null) {
            Utils.recycleNode(targetFocusArea);
            return cachedFocusedNode;
        }

        // Make a list of candidate nodes in the target FocusArea.
        List<AccessibilityNodeInfo> candidateNodes = new ArrayList<>();
        addFocusDescendants(targetFocusArea, candidateNodes);

        // Choose the best candidate as the target node.
        AccessibilityNodeInfo bestCandidate =
                chooseBestNudgeCandidate(sourceNode, candidateNodes, direction);

        Utils.recycleNodes(candidateNodes);
        Utils.recycleNode(targetFocusArea);
        return bestCandidate;
    }

    /**
     * Returns the target focusable for a nudge to the HUN if the HUN is present and the nudge is
     * in the right direction. The target focusable is chosen as follows:
     * <ol>
     *     <li>The best candidate focus area is chosen. If there aren't any valid candidates, the
     *         first (only) focus area in the HUN is used. This happens when nudging from a view
     *         obscured by the HUN.
     *     <li>The focus history is checked. If one of the focusable views in the chosen focus area
     *         is in the cache, it's returned.
     *     <li>Finally the best candidate focusable view in the chosen focus area is selected.
     *         Again, if there aren't any candidates, the first focusable view is chosen.
     * </ol>
     * The caller is responsible for recycling the result.
     *
     * @param windows    a list of windows to search from
     * @param sourceNode the current focus
     * @param direction  nudge direction, must be {@link View#FOCUS_UP}, {@link View#FOCUS_DOWN},
     *                   {@link View#FOCUS_LEFT}, or {@link View#FOCUS_RIGHT}
     * @return a view that can take focus (visible, focusable and enabled) within the HUN, or null
     *         if the HUN isn't present, the nudge isn't in the direction of the HUN, or the HUN
     *         contains no views that can take focus
     */
    private AccessibilityNodeInfo findHunNudgeTarget(@NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityNodeInfo sourceNode, int direction) {
        if (direction != mHunNudgeDirection) {
            return null;
        }

        // Find the HUN window, if any.
        AccessibilityWindowInfo hunWindow = null;
        for (AccessibilityWindowInfo window : windows) {
            if (isHunWindow(window)) {
                hunWindow = window;
                break;
            }
        }
        if (hunWindow == null) {
            return null;
        }

        // Find the target focus area within the HUN. The HUN may overlap the source node, in which
        // case the geometric search will fail. The fallback is to use the first (typically only)
        // focus area.
        List<AccessibilityNodeInfo> hunFocusAreas = findFocusAreas(hunWindow);
        removeEmptyFocusAreas(hunFocusAreas);
        AccessibilityNodeInfo targetFocusArea =
                chooseBestNudgeCandidate(sourceNode, hunFocusAreas, direction);
        if (targetFocusArea == null && !hunFocusAreas.isEmpty()) {
            targetFocusArea = copyNode(hunFocusAreas.get(0));
        }
        Utils.recycleNodes(hunFocusAreas);
        if (targetFocusArea == null) {
            return null;
        }

        // Save nudge history.
        AccessibilityNodeInfo currentFocusArea = getAncestorFocusArea(sourceNode);
        long elapsedRealtime = SystemClock.elapsedRealtime();
        mRotaryCache.saveTargetFocusArea(
                currentFocusArea, targetFocusArea, direction, elapsedRealtime);

        // Check the cache to see if a node was focused in the HUN.
        AccessibilityNodeInfo cachedFocusedNode =
                mRotaryCache.getFocusedNode(targetFocusArea, elapsedRealtime);
        if (cachedFocusedNode != null) {
            Utils.recycleNode(targetFocusArea);
            Utils.recycleNode(currentFocusArea);
            return cachedFocusedNode;
        }

        // Choose the best candidate target node. The HUN may overlap the source node, in which
        // case the geometric search will fail. The fallback is to use the first focusable node.
        List<AccessibilityNodeInfo> candidateNodes = new ArrayList<>();
        addFocusDescendants(targetFocusArea, candidateNodes);
        AccessibilityNodeInfo bestCandidate =
                chooseBestNudgeCandidate(sourceNode, candidateNodes, direction);
        if (bestCandidate == null && !candidateNodes.isEmpty()) {
            bestCandidate = copyNode(candidateNodes.get(0));
        }
        Utils.recycleNodes(candidateNodes);
        Utils.recycleNode(targetFocusArea);
        Utils.recycleNode(currentFocusArea);
        return bestCandidate;
    }

    /**
     * Returns the target focusable for a rotate. The caller is responsible for recycling the node
     * in the result.
     *
     * <p>If {@code skipNode} isn't null, this node will be skipped. This is used when the focus is
     * inside a scrollable container to avoid moving the focus to the scrollable container itself.
     *
     * <p>Limits navigation to focusable views within a scrollable container's viewport, if any.
     *
     * @param sourceNode    the current focus
     * @param skipNode      a node to skip - optional
     * @param direction     rotate direction, must be {@link View#FOCUS_FORWARD} or {@link
     *                      View#FOCUS_BACKWARD}
     * @param rotationCount the number of "ticks" to rotate. Only count nodes that can take focus
     *                      (visible, focusable and enabled). If {@code skipNode} is encountered, it
     *                      isn't counted.
     * @return a FindRotateTargetResult containing a node and a count of the number of times the
     *         search advanced to another node. The node represents a focusable view in the given
     *         {@code direction} from the current focus within the same {@link FocusArea}. If the
     *         first or last view is reached before counting up to {@code rotationCount}, the first
     *         or last view is returned. However, if there are no views that can take focus in the
     *         given {@code direction}, {@code null} is returned.
     */
    @Nullable
    FindRotateTargetResult findRotateTarget(@NonNull AccessibilityNodeInfo sourceNode,
            @Nullable AccessibilityNodeInfo skipNode, int direction, int rotationCount) {
        int advancedCount = 0;
        AccessibilityNodeInfo currentFocusArea = getAncestorFocusArea(sourceNode);
        AccessibilityNodeInfo targetNode = copyNode(sourceNode);
        for (int i = 0; i < rotationCount; i++) {
            AccessibilityNodeInfo nextTargetNode = targetNode.focusSearch(direction);
            if (skipNode != null && skipNode.equals(nextTargetNode)) {
                Utils.recycleNode(nextTargetNode);
                nextTargetNode = skipNode.focusSearch(direction);
            }
            AccessibilityNodeInfo targetFocusArea =
                    nextTargetNode == null ? null : getAncestorFocusArea(nextTargetNode);

            // Only advance to nextTargetNode if it's in the same focus area and it isn't a
            // FocusParkingView. The second condition prevents wrap-around when there is only one
            // focus area in the window, including when the root node is treated as a focus area.
            if (nextTargetNode != null && currentFocusArea.equals(targetFocusArea)
                    && !Utils.isFocusParkingView(nextTargetNode)) {
                // If we're navigating through a scrolling view that can scroll in the specified
                // direction and the next view is off-screen, don't advance to it. (We'll scroll
                // instead.)
                Rect nextTargetBounds = new Rect();
                nextTargetNode.getBoundsInScreen(nextTargetBounds);
                AccessibilityNodeInfo scrollableContainer = findScrollableContainer(targetNode);
                AccessibilityNodeInfo.AccessibilityAction scrollAction =
                        direction == View.FOCUS_FORWARD
                                ? ACTION_SCROLL_FORWARD
                                : ACTION_SCROLL_BACKWARD;
                if (scrollableContainer != null
                        && scrollableContainer.getActionList().contains(scrollAction)) {
                    Rect scrollBounds = new Rect();
                    scrollableContainer.getBoundsInScreen(scrollBounds);
                    boolean intersects = nextTargetBounds.intersect(scrollBounds);
                    if (!intersects) {
                        Utils.recycleNode(nextTargetNode);
                        Utils.recycleNode(targetFocusArea);
                        break;
                    }
                }
                Utils.recycleNode(scrollableContainer);

                Utils.recycleNode(targetNode);
                Utils.recycleNode(targetFocusArea);
                targetNode = nextTargetNode;
                advancedCount++;
            } else {
                Utils.recycleNode(nextTargetNode);
                Utils.recycleNode(targetFocusArea);
                break;
            }
        }
        Utils.recycleNode(currentFocusArea);
        if (sourceNode.equals(targetNode)) {
            targetNode.recycle();
            return null;
        }
        return new FindRotateTargetResult(targetNode, advancedCount);
    }

    /**
     * Searches the {@code rootNode} and its descendants in depth-first order, and returns the first
     * focus descendant (a node inside a focus area that can take focus) if any, or returns null if
     * not found. The caller is responsible for recycling the result.
     */
    AccessibilityNodeInfo findFirstFocusDescendant(@NonNull AccessibilityNodeInfo rootNode) {
        // First try finding the first focus area and searching forward from the focus area. This
        // is a quick way to find the first node but it doesn't always work.
        AccessibilityNodeInfo focusDescendant = findFirstFocus(rootNode);
        if (focusDescendant != null) {
            return focusDescendant;
        }

        // Fall back to tree traversal.
        L.w("Falling back to tree traversal");
        focusDescendant = findDepthFirstFocus(rootNode);
        if (focusDescendant == null) {
            L.w("No node can take focus in the current window");
        }
        return focusDescendant;
    }

    /** Sets a mock Utils instance for testing. */
    @VisibleForTesting
    void setNodeCopier(@NonNull NodeCopier nodeCopier) {
        mNodeCopier = nodeCopier;
        mTreeTraverser.setNodeCopier(nodeCopier);
        mRotaryCache.setNodeCopier(nodeCopier);
    }

    /**
     * Searches all the nodes in the {@code window}, and returns the node representing a {@link
     * FocusParkingView}, if any, or returns null if not found. The caller is responsible for
     * recycling the result.
     */
    AccessibilityNodeInfo findFocusParkingView(@NonNull AccessibilityWindowInfo window) {
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) {
            L.e("No root node in " + window);
            return null;
        }
        AccessibilityNodeInfo focusParkingView = findFocusParkingView(root);
        root.recycle();
        return focusParkingView;
    }

    /**
     * Searches the {@code node} and its descendants in depth-first order, and returns the node
     * representing a {@link FocusParkingView}, if any, or returns null if not found. The caller is
     * responsible for recycling the result.
     */
    private AccessibilityNodeInfo findFocusParkingView(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.depthFirstSearch(node, /* skipPredicate= */ Utils::isFocusArea,
                /* targetPredicate= */ Utils::isFocusParkingView);
    }

    /**
     * Searches the {@code rootNode} and its descendants in depth-first order for the first focus
     * area, and returns the first node that can take focus in tab order from the focus area.
     * The return value could be a node inside or outside the first focus area, or null if not
     * found. The caller is responsible for recycling result.
     */
    private AccessibilityNodeInfo findFirstFocus(@NonNull AccessibilityNodeInfo rootNode) {
        AccessibilityNodeInfo focusArea = findFirstFocusArea(rootNode);
        if (focusArea == null) {
            L.w("No FocusArea in the tree");
            // rootNode is an implicit focus area if no explicit FocusAreas are specified.
            focusArea = copyNode(rootNode);
        }

        AccessibilityNodeInfo targetNode = focusArea.focusSearch(View.FOCUS_FORWARD);
        AccessibilityNodeInfo firstTarget = copyNode(targetNode);
        // focusSearch() searches in the active window, which has at least one FocusParkingView. We
        // need to skip it.
        while (targetNode != null && Utils.isFocusParkingView(targetNode)) {
            L.d("Found FocusParkingView, continue focusSearch() ...");
            AccessibilityNodeInfo nextTargetNode = targetNode.focusSearch(View.FOCUS_FORWARD);
            targetNode.recycle();
            targetNode = nextTargetNode;

            // If we found the same FocusParkingView again, it means all the focusable views in
            // current window are FocusParkingViews, so we should just return null.
            if (firstTarget.equals(targetNode)) {
                L.w("Stop focusSearch() because there is no view to take focus except "
                        + "FocusParkingViews");
                Utils.recycleNode(targetNode);
                targetNode = null;
                break;
            }
        }
        Utils.recycleNode(firstTarget);
        focusArea.recycle();
        return targetNode;
    }

    /**
     * Searches the given {@code node} and its descendants in depth-first order, and returns the
     * first {@link FocusArea}, or returns null if not found. The caller is responsible for
     * recycling the result.
     */
    private AccessibilityNodeInfo findFirstFocusArea(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.depthFirstSearch(node, Utils::isFocusArea);
    }

    /**
     * Searches the given {@code node} and its descendants in depth-first order, and returns the
     * first node that can take focus, or returns null if not found. The caller is responsible for
     * recycling result.
     */
    private AccessibilityNodeInfo findDepthFirstFocus(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.depthFirstSearch(node, Utils::canTakeFocus);
    }

    /**
     * Returns the target focus area for a nudge in the given {@code direction} from the current
     * focus, or null if not found. Checks the cache first. If nothing is found in the cache,
     * returns the best nudge target from among all the candidate focus areas. In all cases, the
     * nudge back is saved in the cache. The caller is responsible for recycling the result.
     */
    private AccessibilityNodeInfo findNudgeTargetFocusArea(
            @NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityNodeInfo focusedNode,
            @NonNull AccessibilityNodeInfo currentFocusArea,
            int direction) {
        long elapsedRealtime = SystemClock.elapsedRealtime();
        // If there is a target focus area in the cache, returns it.
        AccessibilityNodeInfo cachedTargetFocusArea =
                mRotaryCache.getTargetFocusArea(currentFocusArea, direction, elapsedRealtime);
        if (cachedTargetFocusArea != null && Utils.canHaveFocus(cachedTargetFocusArea)) {
            // We already got nudge history in the cache. Before nudging back, let's save "nudge
            // back" history.
            mRotaryCache.saveTargetFocusArea(
                    currentFocusArea, cachedTargetFocusArea, direction, elapsedRealtime);
            return cachedTargetFocusArea;
        }
        Utils.recycleNode(cachedTargetFocusArea);

        // No target focus area in the cache; we need to search the node tree to find it.
        AccessibilityWindowInfo currentWindow = focusedNode.getWindow();
        if (currentWindow == null) {
            L.e("Currently focused window is null");
            return null;
        }

        // Build a list of candidate focus areas, starting with all the other focus areas in the
        // same window as the current focus area.
        List<AccessibilityNodeInfo> candidateFocusAreas = findFocusAreas(currentWindow);
        for (AccessibilityNodeInfo focusArea : candidateFocusAreas) {
            if (focusArea.equals(currentFocusArea)) {
                candidateFocusAreas.remove(focusArea);
                focusArea.recycle();
                break;
            }
        }

        // Add candidate focus areas in other windows in the given direction.
        List<AccessibilityWindowInfo> candidateWindows = new ArrayList<>();
        addWindowsInDirection(windows, currentWindow, candidateWindows, direction);
        currentWindow.recycle();
        for (AccessibilityWindowInfo window : candidateWindows) {
            List<AccessibilityNodeInfo> focusAreasInAnotherWindow = findFocusAreas(window);
            candidateFocusAreas.addAll(focusAreasInAnotherWindow);
        }

        // Exclude focus areas that have no descendants to take focus, because once we found a best
        // candidate focus area, we don't dig into other ones. If it has no descendants to take
        // focus, the nudge will fail.
        removeEmptyFocusAreas(candidateFocusAreas);

        // Choose the best candidate as our target focus area.
        AccessibilityNodeInfo targetFocusArea =
                chooseBestNudgeCandidate(focusedNode, candidateFocusAreas, direction);
        Utils.recycleNodes(candidateFocusAreas);

        if (targetFocusArea != null) {
            // Save nudge history.
            mRotaryCache.saveTargetFocusArea(
                    currentFocusArea, targetFocusArea, direction, elapsedRealtime);
        }

        return targetFocusArea;
    }

    private static void removeEmptyFocusAreas(@NonNull List<AccessibilityNodeInfo> focusAreas) {
        for (Iterator<AccessibilityNodeInfo> iterator = focusAreas.iterator();
                iterator.hasNext(); ) {
            AccessibilityNodeInfo focusArea = iterator.next();
            if (!Utils.canHaveFocus(focusArea)) {
                iterator.remove();
                focusArea.recycle();
            }
        }
    }

    /**
     * Adds all the {@code windows} in the given {@code direction} of the given {@code source}
     * window to the given list.
     */
    private void addWindowsInDirection(@NonNull List<AccessibilityWindowInfo> windows,
            @NonNull AccessibilityWindowInfo source,
            @NonNull List<AccessibilityWindowInfo> results,
            int direction) {
        Rect sourceBounds = new Rect();
        source.getBoundsInScreen(sourceBounds);
        Rect destBounds = new Rect();
        for (AccessibilityWindowInfo window : windows) {
            if (!window.equals(source)) {
                window.getBoundsInScreen(destBounds);

                // Even if only part of destBounds is in the given direction of sourceBounds, we
                // still include it because that part may contain the target focus area.
                if (FocusFinder.isPartiallyInDirection(sourceBounds, destBounds, direction)) {
                    results.add(window);
                }
            }
        }
    }

    /**
     * Scans the view hierarchy of the given {@code window} looking for focus areas and returns
     * them. If there are no explicitly declared {@link FocusArea}s, returns the root view. The
     * caller is responsible for recycling the result.
     */
    private @NonNull
    List<AccessibilityNodeInfo> findFocusAreas(@NonNull AccessibilityWindowInfo window) {
        List<AccessibilityNodeInfo> results = new ArrayList<>();
        AccessibilityNodeInfo rootNode = window.getRoot();
        if (rootNode != null) {
            addFocusAreas(rootNode, results);
            if (results.isEmpty()) {
                results.add(copyNode(rootNode));
            }
            rootNode.recycle();
        }
        return results;
    }

    /**
     * Returns whether the given window is the Heads-up Notification (HUN) window. The HUN window
     * is identified by the left and right edges. The top and bottom vary depending on whether the
     * HUN appears at the top or bottom of the screen and on the height of the notification being
     * displayed so they aren't used.
     */
    boolean isHunWindow(@NonNull AccessibilityWindowInfo window) {
        if (window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) {
            return false;
        }
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        return bounds.left == mHunLeft && bounds.right == mHunRight;
    }

    /**
     * Searches from the given node up through its ancestors to the containing focus area, looking
     * for a node that's marked as horizontally or vertically scrollable. Returns a copy of the
     * first such node or null if none is found. The caller is responsible for recycling the result.
     */
    @Nullable
    AccessibilityNodeInfo findScrollableContainer(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.findNodeOrAncestor(node, /* stopPredicate= */ Utils::isFocusArea,
                /* targetPredicate= */ Utils::isScrollableContainer);
    }

    /**
     * Returns the previous node in Tab order before {@code referenceNode} within
     * {@code containerNode} or null if none. The caller is responsible for recycling the result.
     */
    @Nullable
    static AccessibilityNodeInfo findPreviousFocusableDescendant(
            @NonNull AccessibilityNodeInfo containerNode,
            @NonNull AccessibilityNodeInfo referenceNode) {
        return findFocusableDescendantInDirection(containerNode, referenceNode,
                View.FOCUS_BACKWARD);
    }

    /**
     * Returns the next node after {@code referenceNode} in Tab order within {@code containerNode}
     * or null if none. The caller is responsible for recycling the result.
     */
    @Nullable
    static AccessibilityNodeInfo findNextFocusableDescendant(
            @NonNull AccessibilityNodeInfo containerNode,
            @NonNull AccessibilityNodeInfo referenceNode) {
        return findFocusableDescendantInDirection(containerNode, referenceNode, View.FOCUS_FORWARD);
    }

    /**
     * Returns the previous node before {@code referenceNode} in Tab order or the next node after
     * {@code referenceNode} in Tab order, depending on {@code direction}. The search is limited to
     * descendants of {@code containerNode}. Returns null if there are no focusable descendants in
     * the given direction. The caller is responsible for recycling the result.
     *
     * @param containerNode the node with descendants
     * @param referenceNode a descendant of {@code containerNode} to start from
     * @param direction {@link View#FOCUS_FORWARD} or {@link View#FOCUS_BACKWARD}
     * @return the node before or after {@code referenceNode} or null if none
     */
    @Nullable
    static AccessibilityNodeInfo findFocusableDescendantInDirection(
            @NonNull AccessibilityNodeInfo containerNode,
            @NonNull AccessibilityNodeInfo referenceNode,
            int direction) {
        AccessibilityNodeInfo targetNode = referenceNode.focusSearch(direction);
        if (targetNode == null
                || targetNode.equals(containerNode)
                || !Utils.canTakeFocus(targetNode)
                || !Utils.isDescendant(containerNode, targetNode)) {
            Utils.recycleNode(targetNode);
            return null;
        }
        if (targetNode.equals(referenceNode)) {
            L.w((direction == View.FOCUS_FORWARD ? "Next" : "Previous")
                    + " node is the same node: " + referenceNode);
            Utils.recycleNode(targetNode);
            return null;
        }
        return targetNode;
    }

    /**
     * Returns the first descendant of {@code node} which can take focus. The nodes are searched in
     * in depth-first order, not including {@code node} itself. If no descendant can take focus,
     * null is returned. The caller is responsible for recycling the result.
     */
    @Nullable
    AccessibilityNodeInfo findFirstFocusableDescendant(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.depthFirstSearch(node,
                candidateNode -> candidateNode != node && Utils.canTakeFocus(candidateNode));
    }

    /**
     * Returns the last descendant of {@code node} which can take focus. The nodes are searched in
     * reverse depth-first order, not including {@code node} itself. If no descendant can take
     * focus, null is returned. The caller is responsible for recycling the result.
     */
    @Nullable
    AccessibilityNodeInfo findLastFocusableDescendant(@NonNull AccessibilityNodeInfo node) {
        return mTreeTraverser.reverseDepthFirstSearch(node,
                candidateNode -> candidateNode != node && Utils.canTakeFocus(candidateNode));
    }

    /**
     * Scans descendants of the given {@code rootNode} looking for focus areas and adds them to the
     * given list. It doesn't scan inside focus areas since nested focus areas aren't allowed. The
     * caller is responsible for recycling added nodes.
     *
     * @param rootNode the root to start scanning from
     * @param results  a list of focus areas to add to
     */
    private void addFocusAreas(@NonNull AccessibilityNodeInfo rootNode,
            @NonNull List<AccessibilityNodeInfo> results) {
        mTreeTraverser.depthFirstSelect(rootNode, Utils::isFocusArea, results);
    }

    /**
     * Adds the given {@code node} and all its focus descendants (nodes that can take focus) to the
     * given list. The caller is responsible for recycling added nodes.
     */
    private void addFocusDescendants(@NonNull AccessibilityNodeInfo node,
            @NonNull List<AccessibilityNodeInfo> results) {
        mTreeTraverser.depthFirstSelect(node, Utils::canTakeFocus, results);
    }

    /**
     * Returns a copy of the best candidate from among the given {@code candidates} for a nudge
     * from {@code sourceNode} in the given {@code direction}. Returns null if none of the {@code
     * candidates} are in the given {@code direction}. The caller is responsible for recycling the
     * result.
     *
     * @param candidates could be a list of {@link FocusArea}s, or a list of focusable views
     */
    private AccessibilityNodeInfo chooseBestNudgeCandidate(
            @NonNull AccessibilityNodeInfo sourceNode,
            @NonNull List<AccessibilityNodeInfo> candidates,
            int direction) {
        if (candidates.isEmpty()) {
            return null;
        }
        Rect sourceBounds = new Rect();
        sourceNode.getBoundsInScreen(sourceBounds);

        AccessibilityNodeInfo bestNode = null;
        Rect bestBounds = new Rect();

        Rect candidateBounds = new Rect();
        for (AccessibilityNodeInfo candidate : candidates) {
            if (isCandidate(sourceBounds, candidate, direction)) {
                candidate.getBoundsInScreen(candidateBounds);
                if (bestNode == null || FocusFinder.isBetterCandidate(
                        direction, sourceBounds, candidateBounds, bestBounds)) {
                    bestNode = candidate;
                    bestBounds.set(candidateBounds);
                }
            }
        }
        return copyNode(bestNode);
    }

    /**
     * Returns whether the given {@code node} is a candidate from {@code sourceBounds} to the given
     * {@code direction}. To be a candidate, the node or one of its descendants must be able to take
     * focus and must be considered a candidate by {@link FocusFinder#isCandidate}.
     */
    private boolean isCandidate(@NonNull Rect sourceBounds,
            @NonNull AccessibilityNodeInfo node,
            int direction) {
        AccessibilityNodeInfo candidate = mTreeTraverser.depthFirstSearch(node, candidateNode -> {
            // First check if the node can take focus.
            if (!Utils.canTakeFocus(candidateNode)) {
                return false;
            }
            // The node represents a focusable view in the FocusArea, so check the geometry.
            Rect candidateBounds = new Rect();
            candidateNode.getBoundsInScreen(candidateBounds);
            return FocusFinder.isCandidate(sourceBounds, candidateBounds, direction);
        });
        if (candidate == null) {
            return false;
        }
        candidate.recycle();
        return true;
    }

    private AccessibilityNodeInfo copyNode(@Nullable AccessibilityNodeInfo node) {
        return mNodeCopier.copy(node);
    }

    /**
     * Finds the closest ancestor focus area of the given {@code node}. If the given {@code node}
     * is a focus area, returns it; if there are no explicitly declared {@link FocusArea}s among the
     * ancestors of this view, returns the root view. The caller is responsible for recycling the
     * result.
     */
    @NonNull
    private AccessibilityNodeInfo getAncestorFocusArea(@NonNull AccessibilityNodeInfo node) {
        TreeTraverser.NodePredicate isFocusAreaOrRoot = candidateNode -> {
            if (Utils.isFocusArea(candidateNode)) {
                // The candidateNode is a focus area.
                return true;
            }
            AccessibilityNodeInfo parent = candidateNode.getParent();
            if (parent == null) {
                // The candidateNode is the root node.
                return true;
            }
            parent.recycle();
            return false;
        };
        AccessibilityNodeInfo result = mTreeTraverser.findNodeOrAncestor(node, isFocusAreaOrRoot);
        if (!Utils.isFocusArea(result)) {
            L.w("Couldn't find ancestor focus area for given node: " + node);
        }
        return result;
    }

    /** Result from {@link #findRotateTarget}. */
    static class FindRotateTargetResult {
        @NonNull final AccessibilityNodeInfo node;
        final int advancedCount;

        FindRotateTargetResult(@NonNull AccessibilityNodeInfo node, int advancedCount) {
            this.node = node;
            this.advancedCount = advancedCount;
        }
    }
}
